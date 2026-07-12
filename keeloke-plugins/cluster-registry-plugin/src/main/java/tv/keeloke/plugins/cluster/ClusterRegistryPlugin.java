package tv.keeloke.plugins.cluster;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.plugin.api.IStreamListener;
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
import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeloke TV Server - Cluster Registry Plugin.
 *
 * Groundwork for horizontal scaling: every node running this plugin registers
 * itself (host, ports, live-stream count) in a shared Redis map and refreshes
 * a heartbeat every 5s. A REST endpoint (see ClusterRestService) lists which
 * nodes are currently alive.
 *
 * What this gives you today:
 *   - Service discovery: know which Keeloke TV Server nodes exist and how loaded they are.
 *   - The building block a load balancer / origin-selection script needs.
 *
 * What this does NOT give you (this is the honest line between "foundation"
 * and "Enterprise clustering"): automatic stream migration between nodes,
 * origin/edge pull routing, shared viewer session state, or failover of a
 * broadcast if its origin node dies. Building those correctly needs
 * cluster-aware changes inside the media pipeline itself, not just an
 * external plugin - see CLUSTERING_AND_SCALE_ROADMAP.md.
 */
@Component
public class ClusterRegistryPlugin implements IStreamListener, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRegistryPlugin.class);
    private static final String REGISTRY_MAP_NAME = "keeloke:cluster:nodes";
    private static final long HEARTBEAT_TTL_SECONDS = 15;

    @Value("${keeloke.cluster.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    @Value("${server.rtmp_port:1935}")
    private int rtmpPort;

    @Value("${server.http_port:5080}")
    private int httpPort;

    private ApplicationContext applicationContext;
    private RedissonClient redisson;
    private final String nodeId = UUID.randomUUID().toString();
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);
    private ScheduledExecutorService heartbeatExecutor;

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

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleAtFixedRate(this::heartbeat, 0, 5, TimeUnit.SECONDS);

        logger.info("Keeloke Cluster Registry Plugin initialized. nodeId={} redis={}", nodeId, redisAddress);
    }

    @PreDestroy
    public void shutdown() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        if (redisson != null) {
            RMap<String, ClusterNodeInfo> registry = redisson.getMap(REGISTRY_MAP_NAME);
            registry.remove(nodeId);
            redisson.shutdown();
        }
    }

    private void heartbeat() {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            ClusterNodeInfo info = new ClusterNodeInfo(nodeId, host, rtmpPort, httpPort,
                    activeStreamCount.get(), System.currentTimeMillis());

            RMap<String, ClusterNodeInfo> registry = redisson.getMap(REGISTRY_MAP_NAME);
            registry.put(nodeId, info);
            registry.expire(java.time.Duration.ofSeconds(HEARTBEAT_TTL_SECONDS));
        } catch (Exception e) {
            logger.warn("Cluster heartbeat failed (is Redis reachable at {}?): {}", redisAddress, e.getMessage());
        }
    }

    public Map<String, ClusterNodeInfo> listNodes() {
        RMap<String, ClusterNodeInfo> registry = redisson.getMap(REGISTRY_MAP_NAME);
        long cutoff = System.currentTimeMillis() - (HEARTBEAT_TTL_SECONDS * 1000);
        Map<String, ClusterNodeInfo> all = registry.readAllMap();
        all.values().removeIf(n -> n.getLastHeartbeatEpochMs() < cutoff);
        return all;
    }

    @Override
    public void streamStarted(Broadcast broadcast) {
        activeStreamCount.incrementAndGet();
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        activeStreamCount.decrementAndGet();
    }

    @Override
    public void joinedTheRoom(String roomId, String streamId) {
        // not tracked by the node registry
    }

    @Override
    public void leftTheRoom(String roomId, String streamId) {
        // not tracked by the node registry
    }
}
