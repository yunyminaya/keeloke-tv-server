package tv.keeloke.plugins.cluster;

import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Detects nodes whose heartbeat has expired and handles the aftermath:
 *   1. finds the streams that node was the origin of (orphaned broadcasts),
 *   2. removes them from the origin registry (so edges stop pulling a dead origin),
 *   3. removes the dead node from the registry,
 *   4. fires a failover webhook so an external system - or the publishing client -
 *      can react (a live publisher must reconnect; there's no way for one server
 *      to resurrect another node's inbound RTMP/WebRTC session, so we surface the
 *      event and the recommended replacement node rather than pretending to
 *      transparently migrate a live ingest).
 *
 * A Redis lock ensures only one surviving node performs the sweep each cycle,
 * so N nodes don't all race to clean up and fire N duplicate webhooks.
 */
@Component
public class ClusterFailoverMonitor {

    private static final Logger logger = LoggerFactory.getLogger(ClusterFailoverMonitor.class);
    private static final String SWEEP_LOCK = "keeloke:cluster:failover-lock";

    @Autowired
    private ClusterRedis clusterRedis;

    @Autowired
    private ClusterCoordinatorConfig config;

    @Autowired
    private StreamOriginRegistry originRegistry;

    @Autowired
    private ClusterLoadBalancer loadBalancer;

    private ScheduledExecutorService executor;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @PostConstruct
    public void init() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "keeloke-cluster-failover");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::sweep, 10, 10, TimeUnit.SECONDS);
        logger.info("Keeloke ClusterFailoverMonitor started");
    }

    @PreDestroy
    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void sweep() {
        RLock lock = clusterRedis.client().getLock(SWEEP_LOCK);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 8, TimeUnit.SECONDS);
            if (!acquired) {
                return; // another node is sweeping this cycle
            }
            doSweep();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Failover sweep error: {}", e.getMessage());
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void doSweep() {
        RMap<String, ClusterNodeInfo> registry = clusterRedis.client().getMap(ClusterRegistryPlugin.REGISTRY_MAP_NAME);
        long cutoff = System.currentTimeMillis() - (ClusterRegistryPlugin.HEARTBEAT_TTL_SECONDS * 1000);

        for (Map.Entry<String, ClusterNodeInfo> entry : registry.readAllMap().entrySet()) {
            ClusterNodeInfo node = entry.getValue();
            if (node.getLastHeartbeatEpochMs() >= cutoff) {
                continue; // alive
            }

            String deadNodeId = entry.getKey();
            Set<String> orphaned = originRegistry.streamsOnNode(deadNodeId);
            logger.warn("Node {} ({}) failed - heartbeat expired. Orphaned {} stream(s): {}",
                    deadNodeId, node.getHost(), orphaned.size(), orphaned);

            String replacement = loadBalancer.selectPublishNode(node.getRegion())
                    .map(ClusterNodeInfo::getNodeId).orElse(null);

            for (String streamId : orphaned) {
                originRegistry.removeOrigin(streamId);
                notifyFailover(deadNodeId, node.getHost(), streamId, replacement);
            }
            registry.remove(deadNodeId);
        }
    }

    private void notifyFailover(String deadNodeId, String deadHost, String streamId, String replacementNodeId) {
        String url = config.getFailoverWebhookUrl();
        if (url == null || url.isBlank()) {
            return;
        }
        String body = "{"
                + "\"event\":\"node.failover\","
                + "\"deadNodeId\":\"" + escape(deadNodeId) + "\","
                + "\"deadHost\":\"" + escape(deadHost) + "\","
                + "\"orphanedStreamId\":\"" + escape(streamId) + "\","
                + "\"recommendedReplacementNodeId\":" + (replacementNodeId != null ? "\"" + escape(replacementNodeId) + "\"" : "null") + ","
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
            logger.warn("Failover webhook to {} failed: {}", url, e.getMessage());
        }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
