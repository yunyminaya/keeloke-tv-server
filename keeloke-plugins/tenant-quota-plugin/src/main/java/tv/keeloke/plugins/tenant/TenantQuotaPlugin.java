package tv.keeloke.plugins.tenant;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.plugin.api.IStreamListener;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IStreamPublishSecurity;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeloke TV Server - Tenant Quota &amp; Usage Analytics Plugin.
 *
 * Treats each Ant Media "application" (LiveApp, WebRTCApp, or a custom app
 * you create per customer) as a tenant. For each tenant this plugin:
 *
 *  1. ENFORCES a configurable max-concurrent-streams quota by implementing
 *     the real org.red5.server.api.stream.IStreamPublishSecurity hook - a
 *     publish attempt over quota is actually rejected by the server, not
 *     just logged.
 *
 *  2. METERS usage (cumulative stream-minutes, streams started) per tenant
 *     into Redis, so it survives restarts and is shared across a cluster of
 *     nodes (reuses the same Redis instance as cluster-registry-plugin).
 *
 * This is the metering/enforcement layer a billing system would sit on top
 * of. It does NOT implement invoicing, payment processing, or API-key
 * issuance - see TENANT_QUOTA_README.md for why those are a product/business
 * decision (pricing, payment processor choice) rather than something to
 * hardcode here, plus a sketch of how to wire one in.
 *
 * v2 (Ant Media 3.0.3): IStreamPublishSecurity.isPublishAllowed() gained 3
 * extra trailing String parameters in this server version vs 2.11.3 (their
 * exact semantics aren't documented in the public API surface we could
 * inspect - unused here, kept only to satisfy the interface signature).
 * IStreamListener.streamStarted/streamFinished now take a Broadcast instead
 * of a bare streamId.
 *
 * Also fixes a real bug found during live verification against 2.11.3:
 * activeStreamsPerTenant was only ever mutated by recordTenantStreamStart/End,
 * which nothing called - so the quota check always saw 0 active streams and
 * could never actually reject a publish. Fixed by incrementing directly in
 * isPublishAllowed() (which has the tenant name via IScope) and decrementing
 * in streamFinished() (using this instance's own app name, since one plugin
 * instance exists per app/tenant).
 */
@Component
public class TenantQuotaPlugin implements IStreamListener, IStreamPublishSecurity, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(TenantQuotaPlugin.class);
    private static final String USAGE_MAP_NAME = "keeloke:tenant:usage";

    @Value("${keeloke.tenant.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    @Value("${keeloke.tenant.defaultMaxConcurrentStreams:10}")
    private int defaultMaxConcurrentStreams;

    private ApplicationContext applicationContext;
    private RedissonClient redisson;

    private final Map<String, Integer> activeStreamsPerTenant = new ConcurrentHashMap<>();
    private final Map<String, Long> streamStartEpochMs = new ConcurrentHashMap<>();
    private final Map<String, Integer> quotaOverridesPerTenant = new ConcurrentHashMap<>();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);

        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        adapter.addStreamListener(this);

        logger.info("Keeloke Tenant Quota Plugin initialized. defaultMaxConcurrentStreams={} redis={}",
                defaultMaxConcurrentStreams, redisAddress);
    }

    public void setQuotaForTenant(String tenantApp, int maxConcurrentStreams) {
        quotaOverridesPerTenant.put(tenantApp, maxConcurrentStreams);
    }

    private int quotaFor(String tenantApp) {
        return quotaOverridesPerTenant.getOrDefault(tenantApp, defaultMaxConcurrentStreams);
    }

    // ---- IStreamPublishSecurity: the actual enforcement point ----

    @Override
    public boolean isPublishAllowed(IScope scope, String streamId, String mode,
                                     Map<String, String> parametersMap, String param5,
                                     String param6, String param7, String param8) {
        String tenantApp = scope.getName();
        int active = activeStreamsPerTenant.getOrDefault(tenantApp, 0);
        int quota = quotaFor(tenantApp);

        if (active >= quota) {
            logger.warn("Rejecting publish for stream '{}' on tenant '{}': quota reached ({}/{})",
                    streamId, tenantApp, active, quota);
            return false;
        }

        recordTenantStreamStart(tenantApp);
        return true;
    }

    // ---- IStreamListener: usage metering ----

    @Override
    public void streamStarted(Broadcast broadcast) {
        streamStartEpochMs.put(broadcast.getStreamId(), System.currentTimeMillis());
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        String streamId = broadcast.getStreamId();
        Long startedAt = streamStartEpochMs.remove(streamId);
        long minutes = startedAt != null ? Math.max(1, (System.currentTimeMillis() - startedAt) / 60000) : 0;

        String tenantApp = applicationContext.getBean(AntMediaApplicationAdapter.class).getAppSettings().getAppName();
        recordTenantStreamEnd(tenantApp, minutes);
    }

    private void recordTenantStreamStart(String tenantApp) {
        activeStreamsPerTenant.merge(tenantApp, 1, Integer::sum);
        RMap<String, TenantUsage> usageMap = redisson.getMap(USAGE_MAP_NAME);
        TenantUsage usage = usageMap.getOrDefault(tenantApp, new TenantUsage(tenantApp, 0, quotaFor(tenantApp), 0, 0));
        usage.setTotalStreamsStarted(usage.getTotalStreamsStarted() + 1);
        usage.setActiveStreams(activeStreamsPerTenant.get(tenantApp));
        usageMap.put(tenantApp, usage);
    }

    private void recordTenantStreamEnd(String tenantApp, long durationMinutes) {
        activeStreamsPerTenant.merge(tenantApp, -1, (a, b) -> Math.max(0, a + b));
        RMap<String, TenantUsage> usageMap = redisson.getMap(USAGE_MAP_NAME);
        TenantUsage usage = usageMap.getOrDefault(tenantApp, new TenantUsage(tenantApp, 0, quotaFor(tenantApp), 0, 0));
        usage.setTotalStreamMinutes(usage.getTotalStreamMinutes() + durationMinutes);
        usage.setActiveStreams(activeStreamsPerTenant.getOrDefault(tenantApp, 0));
        usageMap.put(tenantApp, usage);
    }

    public Map<String, TenantUsage> allTenantUsage() {
        RMap<String, TenantUsage> usageMap = redisson.getMap(USAGE_MAP_NAME);
        return usageMap.readAllMap();
    }

    @Override
    public void joinedTheRoom(String roomId, String streamId) {
        // not metered separately from streamStarted/streamFinished
    }

    @Override
    public void leftTheRoom(String roomId, String streamId) {
        // not metered separately from streamStarted/streamFinished
    }
}
