package tv.keeloke.plugins.security;

import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IStreamPlaybackSecurity;
import org.redisson.Redisson;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Viewer-level analytics: play-session counts and best-effort concurrent
 * viewer tracking per stream, with optional country breakdown (via
 * {@link GeoIpResolver}). Registered as an IStreamPlaybackSecurity bean
 * purely to get a reliable "a viewer started playing X" hook (always
 * returns true - it never blocks playback); actual access control lives in
 * {@link StreamAccessGuard}.
 *
 * Concurrent-viewer count uses a TTL-based presence key per session
 * (refreshed by the player calling the heartbeat REST endpoint) rather than
 * relying on a "play stopped" event, because Ant Media/Red5 doesn't expose a
 * single reliable disconnect hook that fires uniformly across RTMP, HLS and
 * WebRTC playback - a heartbeat is the same tradeoff most viewer-count
 * systems (including Ant Media Enterprise's own dashboard) make in practice.
 */
@Component
public class ViewerAnalyticsPlugin implements IStreamPlaybackSecurity {

    private static final Logger logger = LoggerFactory.getLogger(ViewerAnalyticsPlugin.class);
    private static final long PRESENCE_TTL_SECONDS = 45;

    @Value("${keeloke.security.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    @Autowired
    private GeoIpResolver geoIpResolver;

    @Autowired
    private WebhookDispatcher webhookDispatcher;

    private RedissonClient redisson;

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);
        logger.info("Keeloke ViewerAnalyticsPlugin initialized. redis={}", redisAddress);
    }

    @Override
    public boolean isPlaybackAllowed(IScope scope, String name, int start, int length, boolean flushPlaylist) {
        return true;
    }

    @Override
    public boolean isPlayAllowed(IScope scope, String name, String mode, Map<String, String> queryParams,
                                  String metaData, String token, String subscriberId, String subscriberCode) {
        String tenantApp = scope.getName();
        String sessionId = (subscriberId != null && !subscriberId.isBlank()) ? subscriberId : sessionKeyFallback(name);
        String remoteIp = remoteIp();
        String country = remoteIp != null ? geoIpResolver.countryOf(remoteIp) : "XX";

        totalSessionsCounter(name).incrementAndGet();
        presenceMap(name).put(sessionId, country, PRESENCE_TTL_SECONDS, TimeUnit.SECONDS);
        countryCounter(name, country).incrementAndGet();

        webhookDispatcher.dispatch(tenantApp, "viewer.joined", Map.of(
                "streamId", name, "sessionId", sessionId, "country", country));

        return true; // never blocks - StreamAccessGuard is the enforcement point
    }

    /** Called by the player/client periodically to keep a viewer counted as "present"; also used to record a clean leave. */
    public void heartbeat(String streamId, String sessionId) {
        presenceMap(streamId).put(sessionId, presenceMap(streamId).getOrDefault(sessionId, "XX"), PRESENCE_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public void recordLeave(String tenantApp, String streamId, String sessionId) {
        presenceMap(streamId).remove(sessionId);
        webhookDispatcher.dispatch(tenantApp, "viewer.left", Map.of("streamId", streamId, "sessionId", sessionId));
    }

    public StreamAnalytics snapshot(String streamId) {
        StreamAnalytics analytics = new StreamAnalytics(streamId);
        analytics.setTotalPlaySessions(totalSessionsCounter(streamId).get());
        analytics.setCurrentConcurrentViewers(presenceMap(streamId).size());

        Map<String, Long> byCountry = new HashMap<>();
        for (String country : presenceMap(streamId).values()) {
            byCountry.merge(country, 1L, Long::sum);
        }
        analytics.setViewersByCountry(byCountry);
        return analytics;
    }

    private RAtomicLong totalSessionsCounter(String streamId) {
        return redisson.getAtomicLong("keeloke:analytics:sessions:" + streamId);
    }

    private RAtomicLong countryCounter(String streamId, String country) {
        return redisson.getAtomicLong("keeloke:analytics:country:" + streamId + ":" + country);
    }

    private RMapCache<String, String> presenceMap(String streamId) {
        return redisson.getMapCache("keeloke:analytics:presence:" + streamId);
    }

    private String sessionKeyFallback(String streamId) {
        IConnection connection = Red5.getConnectionLocal();
        return streamId + ":" + (connection != null ? connection.getSessionId() : java.util.UUID.randomUUID().toString());
    }

    private String remoteIp() {
        IConnection connection = Red5.getConnectionLocal();
        return connection != null ? connection.getRemoteAddress() : null;
    }
}
