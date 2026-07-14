package tv.keeloke.plugins.cluster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Locale;

/**
 * Turns the live cluster's aggregate load into a scaling signal for an
 * EXTERNAL autoscaler. Deliberately does NOT hold cloud credentials or call
 * AWS/GCP/K8s APIs to launch or terminate instances itself: a media server
 * with the IAM power to spin up and destroy fleet capacity is a large blast
 * radius and a security liability, and every managed autoscaler (K8s HPA,
 * AWS ASG target-tracking, GCP MIG) is designed to consume a metric, not to
 * be driven imperatively. So this advisor:
 *
 *   - computes the cluster-average loadScore and a SCALE_UP / SCALE_DOWN / HOLD
 *     recommendation (see {@link ClusterCoordinatorConfig} thresholds), and
 *   - exposes it as Prometheus text so an HPA (via prometheus-adapter) or an
 *     ASG/MIG scaling policy (via a CloudWatch/Stackdriver scrape) can act on
 *     it with its own, properly-scoped permissions.
 *
 * That is the honest, production-correct shape of "auto-scaling on AWS/GCP/K8s
 * according to load": Keeloke publishes the signal; the platform's own
 * autoscaler owns the actuation.
 */
@Component
public class ClusterAutoScaleAdvisor {

    public enum Recommendation { SCALE_UP, SCALE_DOWN, HOLD }

    @Autowired
    private ClusterRegistryPlugin registry;

    @Autowired
    private ClusterCoordinatorConfig config;

    public double clusterAverageLoad() {
        Collection<ClusterNodeInfo> nodes = registry.listNodes().values();
        if (nodes.isEmpty()) {
            return 0.0;
        }
        return nodes.stream().mapToDouble(ClusterNodeInfo::loadScore).average().orElse(0.0);
    }

    public int aliveNodeCount() {
        return registry.listNodes().size();
    }

    public long totalActiveStreams() {
        return registry.listNodes().values().stream().mapToLong(ClusterNodeInfo::getActiveStreamCount).sum();
    }

    public Recommendation recommend() {
        int alive = aliveNodeCount();
        double avgLoad = clusterAverageLoad();

        if (avgLoad >= config.getScaleUpThreshold() && alive < config.getMaxNodes()) {
            return Recommendation.SCALE_UP;
        }
        if (avgLoad <= config.getScaleDownThreshold() && alive > config.getMinNodes()) {
            return Recommendation.SCALE_DOWN;
        }
        return Recommendation.HOLD;
    }

    /** Desired node count the external autoscaler could target, derived from load vs the scale-up threshold. */
    public int desiredNodeCount() {
        int alive = Math.max(1, aliveNodeCount());
        double avgLoad = clusterAverageLoad();
        double target = config.getScaleUpThreshold();
        if (target <= 0) {
            return alive;
        }
        int desired = (int) Math.ceil(alive * (avgLoad / target));
        return Math.max(config.getMinNodes(), Math.min(config.getMaxNodes(), desired));
    }

    /** Prometheus text-format exposition consumed by prometheus-adapter (HPA) / CloudWatch scrape (ASG). */
    public String prometheusMetrics() {
        StringBuilder sb = new StringBuilder();
        double avgLoad = clusterAverageLoad();
        int alive = aliveNodeCount();
        long streams = totalActiveStreams();
        int desired = desiredNodeCount();
        int rec = switch (recommend()) {
            case SCALE_UP -> 1;
            case SCALE_DOWN -> -1;
            case HOLD -> 0;
        };

        sb.append("# HELP keeloke_cluster_avg_load Average normalized load across alive nodes (0..1)\n");
        sb.append("# TYPE keeloke_cluster_avg_load gauge\n");
        sb.append(String.format(Locale.ROOT, "keeloke_cluster_avg_load %.4f%n", avgLoad));

        sb.append("# HELP keeloke_cluster_alive_nodes Number of alive nodes\n");
        sb.append("# TYPE keeloke_cluster_alive_nodes gauge\n");
        sb.append("keeloke_cluster_alive_nodes ").append(alive).append("\n");

        sb.append("# HELP keeloke_cluster_active_streams Total active ingested streams across the cluster\n");
        sb.append("# TYPE keeloke_cluster_active_streams gauge\n");
        sb.append("keeloke_cluster_active_streams ").append(streams).append("\n");

        sb.append("# HELP keeloke_cluster_desired_nodes Node count an autoscaler should target\n");
        sb.append("# TYPE keeloke_cluster_desired_nodes gauge\n");
        sb.append("keeloke_cluster_desired_nodes ").append(desired).append("\n");

        sb.append("# HELP keeloke_cluster_scale_recommendation -1 scale down, 0 hold, 1 scale up\n");
        sb.append("# TYPE keeloke_cluster_scale_recommendation gauge\n");
        sb.append("keeloke_cluster_scale_recommendation ").append(rec).append("\n");

        return sb.toString();
    }
}
