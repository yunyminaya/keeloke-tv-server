package tv.keeloke.plugins.cluster;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.plugin.api.IStreamListener;
import org.redisson.api.RMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeloke TV Server - Cluster Registry Plugin.
 *
 * Every node running this plugin registers itself (host, ports, role, region,
 * capacity, live-stream count, CPU load) in a shared Redis map and refreshes a
 * heartbeat every 5s. It also records itself as the ORIGIN of each stream it
 * ingests, so edges and the load balancer can route.
 *
 * The coordination that sits on top of this registry now lives in sibling
 * classes: {@link ClusterLoadBalancer} (least-loaded origin selection + origin
 * lookup for edges), {@link ClusterFailoverMonitor} (detect dead nodes, orphan
 * their streams, notify), and {@link ClusterAutoScaleAdvisor} (emit
 * scale-up/down signals + Prometheus metrics for an external HPA/ASG). The
 * server itself never launches or kills cloud instances - it exposes the load
 * signal an external autoscaler consumes, which is the correct, credential-free
 * boundary. See CLUSTERING_AND_SCALE_ROADMAP.md.
 */
@Component
public class ClusterRegistryPlugin implements IStreamListener, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRegistryPlugin.class);
    static final String REGISTRY_MAP_NAME = "keeloke:cluster:nodes";
    static final long HEARTBEAT_TTL_SECONDS = 15;

    @Value("${server.rtmp_port:1935}")
    private int rtmpPort;

    @Value("${server.http_port:5080}")
    private int httpPort;

    @Autowired
    private ClusterRedis clusterRedis;

    @Autowired
    private ClusterCoordinatorConfig config;

    @Autowired
    private StreamOriginRegistry originRegistry;

    private ApplicationContext applicationContext;
    private final AtomicInteger activeStreamCount = new AtomicInteger(0);
    private ScheduledExecutorService heartbeatExecutor;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        adapter.addStreamListener(this);

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "keeloke-cluster-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::heartbeat, 0, 5, TimeUnit.SECONDS);

        logger.info("Keeloke Cluster Registry Plugin initialized. nodeId={} role={} region={}",
                clusterRedis.nodeId(), config.role(), config.getRegion());
    }

    @PreDestroy
    public void shutdown() {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
        }
        try {
            RMap<String, ClusterNodeInfo> registry = clusterRedis.client().getMap(REGISTRY_MAP_NAME);
            registry.remove(clusterRedis.nodeId());
        } catch (Exception e) {
            logger.warn("Failed to deregister node on shutdown: {}", e.getMessage());
        }
    }

    private void heartbeat() {
        try {
            String host = InetAddress.getLocalHost().getHostAddress();
            ClusterNodeInfo info = new ClusterNodeInfo(clusterRedis.nodeId(), host, rtmpPort, httpPort,
                    activeStreamCount.get(), System.currentTimeMillis());
            info.setRole(config.role());
            info.setRegion(config.getRegion());
            info.setMaxStreamCapacity(config.getMaxStreamCapacity());
            info.setCpuLoadPercent(currentCpuLoadPercent());

            RMap<String, ClusterNodeInfo> registry = clusterRedis.client().getMap(REGISTRY_MAP_NAME);
            registry.put(clusterRedis.nodeId(), info);
        } catch (Exception e) {
            logger.warn("Cluster heartbeat failed (is Redis reachable at {}?): {}", clusterRedis.redisAddress(), e.getMessage());
        }
    }

    private double currentCpuLoadPercent() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double load = sunBean.getCpuLoad(); // 0.0..1.0, or negative if not yet available
                if (load >= 0) {
                    return load * 100.0;
                }
            }
            double avg = bean.getSystemLoadAverage();
            int cores = Math.max(1, bean.getAvailableProcessors());
            return avg >= 0 ? Math.min(100.0, (avg / cores) * 100.0) : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    public Map<String, ClusterNodeInfo> listNodes() {
        RMap<String, ClusterNodeInfo> registry = clusterRedis.client().getMap(REGISTRY_MAP_NAME);
        long cutoff = System.currentTimeMillis() - (HEARTBEAT_TTL_SECONDS * 1000);
        Map<String, ClusterNodeInfo> all = registry.readAllMap();
        all.values().removeIf(n -> n.getLastHeartbeatEpochMs() < cutoff);
        return all;
    }

    @Override
    public void streamStarted(Broadcast broadcast) {
        activeStreamCount.incrementAndGet();
        String app = broadcast != null ? appNameOf(broadcast) : "LiveApp";
        if (broadcast != null && broadcast.getStreamId() != null) {
            originRegistry.registerOrigin(broadcast.getStreamId(), clusterRedis.nodeId(), app);
        }
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        activeStreamCount.updateAndGet(count -> Math.max(0, count - 1));
        if (broadcast != null && broadcast.getStreamId() != null) {
            originRegistry.removeOrigin(broadcast.getStreamId());
        }
    }

    private String appNameOf(Broadcast broadcast) {
        try {
            return applicationContext.getBean(AntMediaApplicationAdapter.class).getAppSettings().getAppName();
        } catch (Exception e) {
            return "LiveApp";
        }
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
