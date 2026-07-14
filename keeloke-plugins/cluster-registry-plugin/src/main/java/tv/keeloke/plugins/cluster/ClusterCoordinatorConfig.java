package tv.keeloke.plugins.cluster;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Operator-facing configuration for cluster coordination, read from
 * application.properties / red5.properties (or environment, via Spring's
 * property resolution). Sensible single-node defaults so a fresh install
 * behaves like today's standalone server until you opt in.
 */
@Component
public class ClusterCoordinatorConfig {

    /** ORIGIN, EDGE, or HYBRID (default). Determines whether this node accepts publishes. */
    @Value("${keeloke.cluster.role:HYBRID}")
    private String role;

    @Value("${keeloke.cluster.region:default}")
    private String region;

    /** Max concurrent ingested streams this node advertises as its capacity. */
    @Value("${keeloke.cluster.maxStreamCapacity:100}")
    private int maxStreamCapacity;

    /** Cluster-average loadScore at/above which the autoscale advisor recommends SCALE_UP. */
    @Value("${keeloke.cluster.scaleUpThreshold:0.75}")
    private double scaleUpThreshold;

    /** Cluster-average loadScore at/below which it recommends SCALE_DOWN (if more than minNodes alive). */
    @Value("${keeloke.cluster.scaleDownThreshold:0.25}")
    private double scaleDownThreshold;

    @Value("${keeloke.cluster.minNodes:1}")
    private int minNodes;

    @Value("${keeloke.cluster.maxNodes:20}")
    private int maxNodes;

    /** Optional webhook notified on node failover / orphaned streams. Blank = disabled. */
    @Value("${keeloke.cluster.failoverWebhookUrl:}")
    private String failoverWebhookUrl;

    public ClusterNodeInfo.Role role() {
        try {
            return ClusterNodeInfo.Role.valueOf(role.trim().toUpperCase());
        } catch (Exception e) {
            return ClusterNodeInfo.Role.HYBRID;
        }
    }

    public String getRegion() { return region; }
    public int getMaxStreamCapacity() { return maxStreamCapacity; }
    public double getScaleUpThreshold() { return scaleUpThreshold; }
    public double getScaleDownThreshold() { return scaleDownThreshold; }
    public int getMinNodes() { return minNodes; }
    public int getMaxNodes() { return maxNodes; }
    public String getFailoverWebhookUrl() { return failoverWebhookUrl; }
}
