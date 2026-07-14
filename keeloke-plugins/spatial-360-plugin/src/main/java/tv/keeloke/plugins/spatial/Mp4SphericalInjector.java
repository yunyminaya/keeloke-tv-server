package tv.keeloke.plugins.spatial;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Inserts the Spherical Video V1 {@code uuid} box into an MP4's first video
 * track, marking it 360°. Operates on the ISO-BMFF box tree:
 *
 *   [ftyp][moov[ trak[ ... ] trak[ ... ] ][mdat]...]
 *
 * It appends the spherical box as the last child of the first {@code trak},
 * then bumps the {@code trak} and {@code moov} 32-bit box sizes accordingly.
 * Handles the common 32-bit-size layout the server's recorder produces; if it
 * meets a 64-bit ({@code size==1}) or to-EOF ({@code size==0}) box where it
 * needs to edit, it bails and leaves the file untouched rather than risk
 * corrupting it.
 */
public final class Mp4SphericalInjector {

    private Mp4SphericalInjector() {
    }

    public enum Status { INJECTED, ALREADY_PRESENT, NO_MOOV, NO_TRAK, UNSUPPORTED_LAYOUT }

    public static class Result {
        public final Status status;
        public final byte[] output; // null unless INJECTED
        public Result(Status status, byte[] output) {
            this.status = status;
            this.output = output;
        }
    }

    /** Injects into a file in place (writes back only if a box was added). */
    public static Status injectFile(Path mp4, boolean stereoTopBottom, SphericalMetadata.Projection projection) throws IOException {
        byte[] input = Files.readAllBytes(mp4);
        Result result = inject(input, stereoTopBottom, projection);
        if (result.status == Status.INJECTED) {
            Files.write(mp4, result.output);
        }
        return result.status;
    }

    public static Result inject(byte[] mp4, boolean stereoTopBottom, SphericalMetadata.Projection projection) {
        long moovOff = findTopLevelBox(mp4, "moov", 0, mp4.length);
        if (moovOff < 0) {
            return new Result(Status.NO_MOOV, null);
        }
        long moovSize = readUint32(mp4, (int) moovOff);
        if (moovSize <= 1) {
            return new Result(Status.UNSUPPORTED_LAYOUT, null); // 64-bit or to-EOF moov
        }
        int moovEnd = (int) (moovOff + moovSize);
        if (moovEnd > mp4.length) {
            return new Result(Status.UNSUPPORTED_LAYOUT, null);
        }

        // find first trak within moov payload
        long trakOff = findBoxIn(mp4, "trak", (int) (moovOff + 8), moovEnd);
        if (trakOff < 0) {
            return new Result(Status.NO_TRAK, null);
        }
        long trakSize = readUint32(mp4, (int) trakOff);
        if (trakSize <= 1) {
            return new Result(Status.UNSUPPORTED_LAYOUT, null);
        }
        int trakEnd = (int) (trakOff + trakSize);

        // idempotency: if the spherical uuid already exists in this trak, do nothing
        if (containsSphericalUuid(mp4, (int) (trakOff + 8), trakEnd)) {
            return new Result(Status.ALREADY_PRESENT, null);
        }

        byte[] box = SphericalMetadata.buildUuidBox(stereoTopBottom, projection);
        int insertPos = trakEnd; // append as last child of trak

        byte[] out = new byte[mp4.length + box.length];
        System.arraycopy(mp4, 0, out, 0, insertPos);
        System.arraycopy(box, 0, out, insertPos, box.length);
        System.arraycopy(mp4, insertPos, out, insertPos + box.length, mp4.length - insertPos);

        // bump trak and moov sizes
        writeUint32(out, (int) trakOff, trakSize + box.length);
        writeUint32(out, (int) moovOff, moovSize + box.length);

        return new Result(Status.INJECTED, out);
    }

    /** Scans sibling boxes in [start,end) for a type; returns its start offset or -1. */
    static long findBoxIn(byte[] data, String type, int start, int end) {
        int pos = start;
        byte[] want = type.getBytes(StandardCharsets.US_ASCII);
        while (pos + 8 <= end) {
            long size = readUint32(data, pos);
            if (matchesType(data, pos + 4, want)) {
                return pos;
            }
            if (size <= 1) {
                return -1; // 64-bit/to-EOF sibling - can't safely walk further
            }
            pos += (int) size;
            if (size <= 0 || pos <= start) {
                return -1;
            }
        }
        return -1;
    }

    static long findTopLevelBox(byte[] data, String type, int start, int end) {
        return findBoxIn(data, type, start, end);
    }

    static boolean containsSphericalUuid(byte[] data, int start, int end) {
        for (int i = start; i + SphericalMetadata.SPHERICAL_UUID.length <= end; i++) {
            boolean all = true;
            for (int j = 0; j < SphericalMetadata.SPHERICAL_UUID.length; j++) {
                if (data[i + j] != SphericalMetadata.SPHERICAL_UUID[j]) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesType(byte[] data, int off, byte[] want) {
        if (off + 4 > data.length) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (data[off + i] != want[i]) {
                return false;
            }
        }
        return true;
    }

    static long readUint32(byte[] data, int off) {
        return ((long) (data[off] & 0xFF) << 24)
                | ((data[off + 1] & 0xFF) << 16)
                | ((data[off + 2] & 0xFF) << 8)
                | (data[off + 3] & 0xFF);
    }

    static void writeUint32(byte[] data, int off, long value) {
        data[off] = (byte) ((value >> 24) & 0xFF);
        data[off + 1] = (byte) ((value >> 16) & 0xFF);
        data[off + 2] = (byte) ((value >> 8) & 0xFF);
        data[off + 3] = (byte) (value & 0xFF);
    }
}
