package tv.keeloke.plugins.spatial;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds the Spherical Video V1 metadata box (Google Spatial Media spec) that
 * marks an MP4 video track as 360°. Players that understand it (YouTube, VLC,
 * GoPro Player, most WebXR/360 web players) then render the video on a sphere.
 *
 * V1 stores an XML blob inside a {@code uuid} box with the well-known
 * spherical UUID, placed inside the video track ({@code trak}) box. This class
 * builds exactly that box payload; {@link Mp4SphericalInjector} inserts it.
 *
 * Server-side 360 "support" is mostly this: the video pixels are ordinary
 * equirectangular frames that pass through the pipeline untouched - what makes
 * it 360 is this metadata telling the player how to project them. That's why
 * this is a lightweight, correct, injector rather than a heavy transcode.
 */
public final class SphericalMetadata {

    /** The fixed UUID that identifies a Spherical Video V1 box (16 bytes). */
    static final byte[] SPHERICAL_UUID = new byte[]{
            (byte) 0xff, (byte) 0xcc, (byte) 0x82, (byte) 0x63,
            (byte) 0xf8, (byte) 0x55, (byte) 0x4a, (byte) 0x93,
            (byte) 0x88, (byte) 0x14, (byte) 0x58, (byte) 0x7a,
            (byte) 0x02, (byte) 0x52, (byte) 0x1f, (byte) 0xdd
    };

    private SphericalMetadata() {
    }

    public enum Projection { EQUIRECTANGULAR, CUBEMAP }

    public static String buildXml(boolean stereoscopicTopBottom, Projection projection) {
        String mode = stereoscopicTopBottom ? "top-bottom" : "mono";
        String proj = projection == Projection.CUBEMAP ? "cubemap" : "equirectangular";
        return "<?xml version=\"1.0\"?>"
                + "<rdf:SphericalVideo xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\" "
                + "xmlns:GSpherical=\"http://ns.google.com/videos/1.0/spherical/\">"
                + "<GSpherical:Spherical>true</GSpherical:Spherical>"
                + "<GSpherical:Stitched>true</GSpherical:Stitched>"
                + "<GSpherical:ProjectionType>" + proj + "</GSpherical:ProjectionType>"
                + "<GSpherical:StereoMode>" + mode + "</GSpherical:StereoMode>"
                + "<GSpherical:StitchingSoftware>Keeloke TV Server</GSpherical:StitchingSoftware>"
                + "</rdf:SphericalVideo>";
    }

    /**
     * Builds the complete {@code uuid} box: [4-byte size][4-byte 'uuid'][16-byte UUID][XML utf-8].
     * @return the full box bytes ready to splice into a trak.
     */
    public static byte[] buildUuidBox(boolean stereoscopicTopBottom, Projection projection) {
        byte[] xml = buildXml(stereoscopicTopBottom, projection).getBytes(StandardCharsets.UTF_8);
        int size = 8 + SPHERICAL_UUID.length + xml.length; // header(8) + uuid(16) + xml

        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        writeUint32(out, size);
        out.writeBytes(new byte[]{'u', 'u', 'i', 'd'});
        out.writeBytes(SPHERICAL_UUID);
        out.writeBytes(xml);
        return out.toByteArray();
    }

    static void writeUint32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }
}
