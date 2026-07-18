package tv.keeloke.plugins.restream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Spawns and supervises one FFmpeg process per (streamId, destination) pair.
 *
 * Each process does a zero-transcode "stream copy" relay:
 *   ffmpeg -re -i rtmp://127.0.0.1:1935/{app}/{streamId} -c copy -f flv {destination.rtmpUrl}
 *
 * This is CPU-cheap (no re-encoding) and is exactly how a simultaneous restream to
 * YouTube/Facebook/Twitch/etc is done in practice. Only FFmpeg (already bundled with
 * Keeloke TV Server under plugins/) is required - no Enterprise license.
 */
public class RestreamProcessManager {

    private static final Logger logger = LoggerFactory.getLogger(RestreamProcessManager.class);

    private final String ffmpegPath;
    private final Map<String, Process> runningProcesses = new ConcurrentHashMap<>();

    public RestreamProcessManager(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    private String key(String streamId, String destinationId) {
        return streamId + "::" + destinationId;
    }

    public void startAll(String sourceRtmpUrl, String streamId, List<RestreamDestination> destinations) {
        for (RestreamDestination destination : destinations) {
            start(sourceRtmpUrl, streamId, destination);
        }
    }

    public void start(String sourceRtmpUrl, String streamId, RestreamDestination destination) {
        String key = key(streamId, destination.getId());
        if (runningProcesses.containsKey(key)) {
            logger.info("Restream already running for {}", key);
            return;
        }

        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-re",
                "-i", sourceRtmpUrl,
                "-c", "copy",
                "-f", "flv",
                destination.getRtmpUrl()
        );
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            runningProcesses.put(key, process);
            logger.info("Started restream '{}' for stream {} -> {}", destination.getName(), streamId, destination.getRtmpUrl());

            // Drain output in a daemon thread so the process never blocks on a full pipe buffer,
            // and so an operator can see why a destination died (auth failure, network drop, etc).
            Thread drain = new Thread(() -> drainAndRestartOnFailure(sourceRtmpUrl, streamId, destination, process), "restream-drain-" + key);
            drain.setDaemon(true);
            drain.start();
        } catch (IOException e) {
            logger.error("Failed to start restream to {} for stream {}", destination.getRtmpUrl(), streamId, e);
        }
    }

    private void drainAndRestartOnFailure(String sourceRtmpUrl, String streamId, RestreamDestination destination, Process process) {
        try (var reader = process.inputReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[restream {}] {}", destination.getName(), line);
            }
        } catch (IOException e) {
            logger.warn("Lost output stream for restream {}", destination.getName());
        }

        String key = key(streamId, destination.getId());
        runningProcesses.remove(key);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            logger.warn("Restream '{}' for stream {} exited with code {}. Not auto-retrying to avoid a crash loop; "
                    + "restart it via the REST API if the destination is back up.", destination.getName(), streamId, exitCode);
        } else {
            logger.info("Restream '{}' for stream {} stopped cleanly.", destination.getName(), streamId);
        }
    }

    public void stop(String streamId, String destinationId) {
        Process process = runningProcesses.remove(key(streamId, destinationId));
        if (process != null) {
            process.destroy();
        }
    }

    public void stopAllForStream(String streamId) {
        runningProcesses.keySet().stream()
                .filter(k -> k.startsWith(streamId + "::"))
                .toList()
                .forEach(k -> {
                    Process p = runningProcesses.remove(k);
                    if (p != null) {
                        p.destroy();
                    }
                });
    }

    public boolean isRunning(String streamId, String destinationId) {
        Process p = runningProcesses.get(key(streamId, destinationId));
        return p != null && p.isAlive();
    }
}
