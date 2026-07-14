package tv.keeloke.plugins.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Origin-selection and edge-routing decisions on top of the live node
 * registry:
 *
 *  - {@link #selectPublishNode}: picks the least-loaded node that can accept a
 *    publish (ORIGIN/HYBRID, under capacity), preferring the caller's region.
 *    A publishing client (or an ingest load-balancer in front of the cluster)
 *    calls this to learn which node's RTMP/WebRTC ingest URL to use.
 *
 *  - {@link #resolvePlaybackOrigin}: given a streamId, returns the node that is
 *    actually ingesting it, so an edge can pull from the right origin.
 */
@Component
public class ClusterLoadBalancer {

    private static final Logger logger = LoggerFactory.getLogger(ClusterLoadBalancer.class);

    @Autowired
    private ClusterRegistryPlugin registry;

    @Autowired
    private StreamOriginRegistry originRegistry;

    /**
     * @param preferredRegion region to prefer, or null/blank for no preference
     * @return the node a new publish should go to, or empty if the cluster is full
     */
    public Optional<ClusterNodeInfo> selectPublishNode(String preferredRegion) {
        Map<String, ClusterNodeInfo> nodes = registry.listNodes();

        Optional<ClusterNodeInfo> inRegion = nodes.values().stream()
                .filter(ClusterNodeInfo::canAcceptPublish)
                .filter(n -> preferredRegion == null || preferredRegion.isBlank() || preferredRegion.equals(n.getRegion()))
                .min(Comparator.comparingDouble(ClusterNodeInfo::loadScore));

        if (inRegion.isPresent()) {
            return inRegion;
        }
        // fall back to any region if the preferred one is saturated
        return nodes.values().stream()
                .filter(ClusterNodeInfo::canAcceptPublish)
                .min(Comparator.comparingDouble(ClusterNodeInfo::loadScore));
    }

    /**
     * @return the origin node currently ingesting {@code streamId}, or empty if
     *         the stream is unknown or its origin node is no longer alive.
     */
    public Optional<ClusterNodeInfo> resolvePlaybackOrigin(String streamId) {
        String originNodeId = originRegistry.originNodeId(streamId);
        if (originNodeId == null) {
            return Optional.empty();
        }
        ClusterNodeInfo node = registry.listNodes().get(originNodeId);
        if (node == null) {
            logger.warn("Origin node {} for stream {} is no longer alive", originNodeId, streamId);
            return Optional.empty();
        }
        return Optional.of(node);
    }

    /** Builds the HLS pull URL an edge would use to fetch a stream from its origin. */
    public String originPullUrl(String streamId) {
        return resolvePlaybackOrigin(streamId).map(node -> {
            String app = originRegistry.originApp(streamId);
            String appName = app != null ? app : "LiveApp";
            return node.httpBaseUrl() + "/" + appName + "/streams/" + streamId + ".m3u8";
        }).orElse(null);
    }
}
