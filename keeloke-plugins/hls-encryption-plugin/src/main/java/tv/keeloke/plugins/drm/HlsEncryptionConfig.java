package tv.keeloke.plugins.drm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator config for HLS AES-128 encryption. Disabled unless keeloke.drm.enabled=true. */
@Component
public class HlsEncryptionConfig {

    @Value("${keeloke.drm.enabled:false}")
    private boolean enabled;

    /** Directory where encrypted HLS output + key files are written (should be served by the HTTP layer). */
    @Value("${keeloke.drm.outputDir:/usr/local/antmedia/webapps/LiveApp/streams/enc}")
    private String outputDir;

    /** Public base URL used to build the key-delivery URI baked into playlists. */
    @Value("${keeloke.drm.publicBaseUrl:http://127.0.0.1:5080}")
    private String publicBaseUrl;

    /** App the key-delivery REST is reached under (for the baked URI). */
    @Value("${keeloke.drm.app:LiveApp}")
    private String app;

    /** HMAC secret gating key delivery. Blank = key delivery is OPEN (encryption still on, but any client can fetch the key). */
    @Value("${keeloke.drm.keySecret:}")
    private String keySecret;

    /** TTL of the token baked into the playlist's key URI. */
    @Value("${keeloke.drm.keyTokenTtlSeconds:21600}")
    private long keyTokenTtlSeconds;

    @Value("${keeloke.drm.ffmpegPath:ffmpeg}")
    private String ffmpegPath;

    @Value("${keeloke.drm.hlsSegmentSeconds:4}")
    private int hlsSegmentSeconds;

    public boolean isEnabled() { return enabled; }
    public String getOutputDir() { return outputDir; }
    public String getPublicBaseUrl() { return publicBaseUrl == null ? "" : publicBaseUrl.replaceAll("/+$", ""); }
    public String getApp() { return app; }
    public String getKeySecret() { return keySecret; }
    public long getKeyTokenTtlSeconds() { return keyTokenTtlSeconds; }
    public String getFfmpegPath() { return (ffmpegPath == null || ffmpegPath.isBlank()) ? "ffmpeg" : ffmpegPath; }
    public int getHlsSegmentSeconds() { return hlsSegmentSeconds; }
}
