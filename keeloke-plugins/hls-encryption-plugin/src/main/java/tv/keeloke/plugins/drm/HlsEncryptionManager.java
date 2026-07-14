package tv.keeloke.plugins.drm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Produces AES-128 encrypted HLS from a live stream by running an FFmpeg
 * stream-copy into the encrypted-HLS muxer:
 *
 *   ffmpeg -i rtmp://127.0.0.1:1935/{app}/{streamId} -c copy \
 *     -hls_time N -hls_list_size 0 \
 *     -hls_key_info_file {keyinfo} \
 *     -hls_segment_filename {dir}/{streamId}_%05d.ts {dir}/{streamId}.m3u8
 *
 * The key URI baked into the playlist points at our token-gated delivery
 * endpoint, so a player can only decrypt with a valid token. No re-encoding
 * (CPU-cheap), only the encryption is added.
 */
@Component
public class HlsEncryptionManager {

    private static final Logger logger = LoggerFactory.getLogger(HlsEncryptionManager.class);

    @Autowired
    private HlsEncryptionConfig config;

    @Autowired
    private HlsKeyStore keyStore;

    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    public void start(String app, String streamId, int rtmpPort) {
        if (!config.isEnabled()) {
            return;
        }
        if (processes.containsKey(streamId)) {
            return;
        }
        try {
            Path dir = Paths.get(config.getOutputDir(), app);
            Files.createDirectories(dir);

            byte[] key = HlsKeyUtil.generateKey();
            String ivHex = HlsKeyUtil.generateIvHex();

            Path keyFile = dir.resolve(streamId + ".key");
            Files.write(keyFile, key);

            String keyDeliveryUri = buildKeyUri(streamId);
            Path keyInfo = dir.resolve(streamId + ".keyinfo");
            Files.writeString(keyInfo, HlsKeyUtil.buildKeyInfoFile(keyDeliveryUri, keyFile.toAbsolutePath().toString(), ivHex));

            keyStore.put(streamId, key);

            String source = "rtmp://127.0.0.1:" + rtmpPort + "/" + app + "/" + streamId;
            ProcessBuilder pb = new ProcessBuilder(
                    config.getFfmpegPath(),
                    "-loglevel", "warning",
                    "-i", source,
                    "-c", "copy",
                    "-hls_time", String.valueOf(config.getHlsSegmentSeconds()),
                    "-hls_list_size", "0",
                    "-hls_flags", "delete_segments+program_date_time",
                    "-hls_key_info_file", keyInfo.toAbsolutePath().toString(),
                    "-hls_segment_filename", dir.resolve(streamId + "_%05d.ts").toString(),
                    dir.resolve(streamId + ".m3u8").toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            processes.put(streamId, process);

            Thread drain = new Thread(() -> drain(streamId, process), "keeloke-drm-" + streamId);
            drain.setDaemon(true);
            drain.start();

            logger.info("HLS AES-128 encryption started for {}/{} -> {}", app, streamId, dir.resolve(streamId + ".m3u8"));
        } catch (IOException e) {
            logger.error("Failed to start HLS encryption for {}: {}", streamId, e.getMessage());
        }
    }

    public void stop(String streamId) {
        Process process = processes.remove(streamId);
        if (process != null) {
            process.destroy();
        }
        keyStore.remove(streamId);
        // leave the key file removal to the OS/cleanup; the key is gone from the store so delivery stops
        try {
            Path dir = Paths.get(config.getOutputDir());
            Files.deleteIfExists(dir.resolve(streamId + ".key"));
        } catch (Exception ignored) {
        }
    }

    String buildKeyUri(String streamId) {
        String base = config.getPublicBaseUrl() + "/" + config.getApp() + "/rest/keeloke/v1/drm/" + streamId + "/key";
        if (config.getKeySecret() != null && !config.getKeySecret().isBlank()) {
            String token = DrmTokenUtil.issue(config.getKeySecret(), streamId, config.getKeyTokenTtlSeconds());
            return base + "?token=" + token;
        }
        return base;
    }

    private void drain(String streamId, Process process) {
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[drm {}] {}", streamId, line);
            }
        } catch (IOException ignored) {
        }
        processes.remove(streamId);
    }
}
