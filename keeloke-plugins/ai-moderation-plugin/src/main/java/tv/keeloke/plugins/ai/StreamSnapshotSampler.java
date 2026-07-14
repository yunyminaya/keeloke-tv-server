package tv.keeloke.plugins.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Grabs a single JPEG frame from a live stream using FFmpeg (already bundled
 * with the server), writing the image to stdout so nothing touches disk:
 *
 *   ffmpeg -i rtmp://127.0.0.1:1935/{app}/{streamId} -frames:v 1 -q:v 3 -f image2pipe -vcodec mjpeg pipe:1
 *
 * Returns the raw JPEG bytes, or null if the grab failed / timed out.
 */
public class StreamSnapshotSampler {

    private static final Logger logger = LoggerFactory.getLogger(StreamSnapshotSampler.class);

    private final String ffmpegPath;

    public StreamSnapshotSampler(String ffmpegPath) {
        this.ffmpegPath = (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath;
    }

    public byte[] grabJpeg(String sourceRtmpUrl) {
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-loglevel", "error",
                "-i", sourceRtmpUrl,
                "-frames:v", "1",
                "-q:v", "3",
                "-f", "image2pipe",
                "-vcodec", "mjpeg",
                "pipe:1"
        );
        pb.redirectErrorStream(false);

        Process process = null;
        try {
            process = pb.start();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                logger.warn("Snapshot grab timed out for {}", sourceRtmpUrl);
                return null;
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) {
                logger.warn("Snapshot grab produced 0 bytes for {} (stream not ready?)", sourceRtmpUrl);
                return null;
            }
            return bytes;
        } catch (Exception e) {
            logger.warn("Snapshot grab failed for {}: {}", sourceRtmpUrl, e.getMessage());
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }
}
