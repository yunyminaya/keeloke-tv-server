package tv.keeloke.plugins.ai;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.plugin.api.IStreamListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI content detection & moderation for live streams, provider-agnostic.
 *
 * On stream start it begins sampling one frame every N seconds (FFmpeg), sends
 * it to whatever AI vision provider you configured an API key for
 * ({@link AiProviderFactory}), and on a flagged frame takes the configured
 * action(s): log, fire a webhook, and/or stop the broadcast. On stream end it
 * stops sampling. Fully disabled until you set a provider + key, so it costs
 * nothing (and calls no external API) out of the box.
 */
@Component
public class AiModerationPlugin implements IStreamListener, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(AiModerationPlugin.class);

    @Value("${server.rtmp_port:1935}")
    private int rtmpPort;

    @Value("${keeloke.ai.ffmpegPath:ffmpeg}")
    private String ffmpegPath;

    @Autowired
    private AiConfigStore configStore;

    private ApplicationContext applicationContext;
    private StreamSnapshotSampler sampler;
    private ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> samplingTasks = new ConcurrentHashMap<>();
    private final Map<String, ModerationResult> lastResults = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        this.sampler = new StreamSnapshotSampler(ffmpegPath);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "keeloke-ai-moderation");
            t.setDaemon(true);
            return t;
        });
        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        adapter.addStreamListener(this);
        logger.info("Keeloke AiModerationPlugin initialized (ffmpeg={})", ffmpegPath);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void streamStarted(Broadcast broadcast) {
        AiModerationConfig config = configStore.get();
        if (!config.isEnabled() || broadcast == null || broadcast.getStreamId() == null) {
            return;
        }
        String streamId = broadcast.getStreamId();
        String app = appName();
        String sourceUrl = "rtmp://127.0.0.1:" + rtmpPort + "/" + app + "/" + streamId;
        int interval = Math.max(3, config.getSampleIntervalSeconds());

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> sampleAndAnalyze(app, streamId, sourceUrl),
                interval, interval, TimeUnit.SECONDS);
        samplingTasks.put(streamId, task);
        logger.info("AI moderation started for stream {} (provider={}, every {}s)", streamId, config.getProvider(), interval);
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        if (broadcast == null || broadcast.getStreamId() == null) {
            return;
        }
        ScheduledFuture<?> task = samplingTasks.remove(broadcast.getStreamId());
        if (task != null) {
            task.cancel(false);
        }
    }

    private void sampleAndAnalyze(String app, String streamId, String sourceUrl) {
        try {
            AiModerationConfig config = configStore.get();
            if (!config.isEnabled()) {
                return;
            }
            byte[] jpeg = sampler.grabJpeg(sourceUrl);
            if (jpeg == null) {
                return;
            }
            AiProvider provider = AiProviderFactory.from(config);
            ModerationResult result = provider.analyzeImage(jpeg).evaluate(config.getFlagThreshold());
            lastResults.put(streamId, result);

            if (result.isFlagged()) {
                handleFlagged(app, streamId, config, result);
            }
        } catch (Exception e) {
            logger.warn("AI sample/analyze error for {}: {}", streamId, e.getMessage());
        }
    }

    private void handleFlagged(String app, String streamId, AiModerationConfig config, ModerationResult result) {
        String actions = config.getActionOnFlag() != null ? config.getActionOnFlag().toLowerCase() : "log";
        logger.warn("AI FLAGGED stream {} ({}): {} labels={}", streamId, result.getProvider(), result.getReason(), result.getDetectedLabels());

        if (actions.contains("webhook") && config.getWebhookUrl() != null && !config.getWebhookUrl().isBlank()) {
            fireWebhook(config.getWebhookUrl(), app, streamId, result);
        }
        if (actions.contains("stop")) {
            stopBroadcast(streamId);
        }
    }

    private void fireWebhook(String url, String app, String streamId, ModerationResult result) {
        String body = "{"
                + "\"event\":\"ai.flagged\","
                + "\"app\":\"" + escape(app) + "\","
                + "\"streamId\":\"" + escape(streamId) + "\","
                + "\"provider\":\"" + escape(result.getProvider()) + "\","
                + "\"reason\":\"" + escape(result.getReason()) + "\","
                + "\"maxScore\":" + result.getMaxScore() + ","
                + "\"timestamp\":" + System.currentTimeMillis()
                + "}";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            logger.warn("AI flag webhook to {} failed: {}", url, e.getMessage());
        }
    }

    private void stopBroadcast(String streamId) {
        try {
            AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
            adapter.stopStreaming(adapter.getDataStore().get(streamId), false, null);
            logger.warn("AI moderation stopped broadcast {}", streamId);
        } catch (Throwable t) {
            // stopStreaming signature varies across server versions; failing to stop must not crash the sampler
            logger.warn("Could not auto-stop broadcast {} ({}). Flag was still logged/webhooked.", streamId, t.getMessage());
        }
    }

    public ModerationResult lastResult(String streamId) {
        return lastResults.get(streamId);
    }

    public ModerationResult analyzeOnce(String streamId) {
        String app = appName();
        String sourceUrl = "rtmp://127.0.0.1:" + rtmpPort + "/" + app + "/" + streamId;
        byte[] jpeg = sampler.grabJpeg(sourceUrl);
        if (jpeg == null) {
            return null;
        }
        AiModerationConfig config = configStore.get();
        return AiProviderFactory.from(config).analyzeImage(jpeg).evaluate(config.getFlagThreshold());
    }

    private String appName() {
        try {
            return applicationContext.getBean(AntMediaApplicationAdapter.class).getAppSettings().getAppName();
        } catch (Exception e) {
            return "LiveApp";
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void joinedTheRoom(String roomId, String streamId) {
    }

    @Override
    public void leftTheRoom(String roomId, String streamId) {
    }
}
