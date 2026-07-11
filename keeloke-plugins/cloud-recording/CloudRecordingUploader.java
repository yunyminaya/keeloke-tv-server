import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Keeloke TV Server - Cloud Recording Uploader.
 *
 * Ant Media Server Community Edition (this install, 2.11.3) does not expose a
 * public "recording finished" plugin hook - that's only wired up internally.
 * Rather than patch the server jar (fragile across upgrades), this watches the
 * recordings directories directly and uploads each finished .mp4 to S3-compatible
 * storage as soon as it stops growing. Works with AWS S3, MinIO, Backblaze B2,
 * Wasabi, etc. - anything the `aws` CLI can talk to via --endpoint-url.
 *
 * Usage:
 *   javac CloudRecordingUploader.java
 *   java CloudRecordingUploader <webappsDir> <s3Bucket> [--endpoint-url=https://...] [--delete-after-upload]
 *
 * Requires the AWS CLI (`aws`) to be installed and configured (env vars,
 * ~/.aws/credentials, or an S3-compatible profile).
 */
public class CloudRecordingUploader {

    private static final long STABLE_CHECK_INTERVAL_MS = 5000;
    private static final int STABLE_CHECKS_REQUIRED = 3; // file size unchanged for 3 consecutive checks -> done writing

    private final Path watchRoot;
    private final String s3Bucket;
    private final String endpointUrl; // nullable -> real AWS S3
    private final boolean deleteAfterUpload;
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(2);
    private final Map<Path, long[]> sizeHistory = new ConcurrentHashMap<>(); // path -> [lastSize, stableCount]

    public CloudRecordingUploader(Path watchRoot, String s3Bucket, String endpointUrl, boolean deleteAfterUpload) {
        this.watchRoot = watchRoot;
        this.s3Bucket = s3Bucket;
        this.endpointUrl = endpointUrl;
        this.deleteAfterUpload = deleteAfterUpload;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java CloudRecordingUploader <webappsDir> <s3Bucket> [--endpoint-url=URL] [--delete-after-upload]");
            System.exit(1);
        }
        Path webappsDir = Paths.get(args[0]);
        String bucket = args[1];
        String endpointUrl = null;
        boolean deleteAfterUpload = false;
        for (int i = 2; i < args.length; i++) {
            if (args[i].startsWith("--endpoint-url=")) {
                endpointUrl = args[i].substring("--endpoint-url=".length());
            } else if (args[i].equals("--delete-after-upload")) {
                deleteAfterUpload = true;
            }
        }

        CloudRecordingUploader uploader = new CloudRecordingUploader(webappsDir, bucket, endpointUrl, deleteAfterUpload);
        uploader.run();
    }

    public void run() throws IOException, InterruptedException {
        System.out.println("Keeloke Cloud Recording Uploader watching: " + watchRoot);
        System.out.println("Target bucket: s3://" + s3Bucket + (endpointUrl != null ? " (endpoint " + endpointUrl + ")" : " (AWS S3)"));

        WatchService watchService = FileSystems.getDefault().newWatchService();
        registerAll(watchRoot, watchService);

        // poll loop: watch for new files, and periodically re-check tracked files for "stopped growing"
        ScheduledExecutorService stabilityChecker = Executors.newSingleThreadScheduledExecutor();
        stabilityChecker.scheduleAtFixedRate(this::checkTrackedFiles, STABLE_CHECK_INTERVAL_MS, STABLE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        while (true) {
            WatchKey key = watchService.take();
            Path dir = (Path) key.watchable();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Path fileName = ((WatchEvent<Path>) event).context();
                Path fullPath = dir.resolve(fileName);

                if (Files.isDirectory(fullPath)) {
                    registerAll(fullPath, watchService);
                    continue;
                }
                if (fullPath.toString().endsWith(".mp4")) {
                    sizeHistory.putIfAbsent(fullPath, new long[]{-1, 0});
                }
            }
            key.reset();
        }
    }

    private void checkTrackedFiles() {
        for (Path path : sizeHistory.keySet()) {
            try {
                if (!Files.exists(path)) {
                    sizeHistory.remove(path);
                    continue;
                }
                long currentSize = Files.size(path);
                long[] history = sizeHistory.get(path);
                if (currentSize == history[0] && currentSize > 0) {
                    history[1]++;
                } else {
                    history[1] = 0;
                }
                history[0] = currentSize;

                if (history[1] >= STABLE_CHECKS_REQUIRED) {
                    sizeHistory.remove(path);
                    uploadExecutor.submit(() -> uploadAndMaybeDelete(path));
                }
            } catch (IOException e) {
                System.err.println("Error checking " + path + ": " + e.getMessage());
            }
        }
    }

    private void uploadAndMaybeDelete(Path path) {
        try {
            String key = watchRoot.relativize(path).toString();
            System.out.println("Uploading finished recording: " + path + " -> s3://" + s3Bucket + "/" + key);

            java.util.List<String> command = new java.util.ArrayList<>();
            command.add("aws");
            command.add("s3");
            command.add("cp");
            command.add(path.toString());
            command.add("s3://" + s3Bucket + "/" + key);
            if (endpointUrl != null) {
                command.add("--endpoint-url");
                command.add(endpointUrl);
            }

            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (var reader = process.inputReader()) {
                reader.lines().forEach(System.out::println);
            }
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("Upload OK: " + key);
                if (deleteAfterUpload) {
                    Files.deleteIfExists(path);
                    System.out.println("Deleted local copy: " + path);
                }
            } else {
                System.err.println("Upload FAILED (exit " + exitCode + ") for " + path + " - left in place for retry.");
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("Upload error for " + path + ": " + e.getMessage());
        }
    }

    private void registerAll(Path start, WatchService watchService) throws IOException {
        if (!Files.exists(start)) {
            return;
        }
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
