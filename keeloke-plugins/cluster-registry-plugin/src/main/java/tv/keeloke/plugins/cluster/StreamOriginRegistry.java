package tv.keeloke.plugins.cluster;

import org.redisson.api.RMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Cluster-shared map of {@code streamId -> origin nodeId}: which node is
 * ingesting (is the origin for) each live stream. This is what lets an edge
 * node answer "where do I pull streamX from?" and what lets the failover
 * monitor know which streams die when a node dies.
 *
 * Value stored is "nodeId|appName" so a lookup yields both the node and the
 * application scope needed to build a pull URL.
 */
@Component
public class StreamOriginRegistry {

    private static final String ORIGIN_MAP_NAME = "keeloke:cluster:stream-origin";

    @Autowired
    private ClusterRedis clusterRedis;

    private RMap<String, String> map() {
        return clusterRedis.client().getMap(ORIGIN_MAP_NAME);
    }

    public void registerOrigin(String streamId, String nodeId, String appName) {
        map().put(streamId, nodeId + "|" + appName);
    }

    public void removeOrigin(String streamId) {
        map().remove(streamId);
    }

    /** @return origin nodeId for a stream, or null if unknown. */
    public String originNodeId(String streamId) {
        String value = map().get(streamId);
        return value != null ? value.split("\\|", 2)[0] : null;
    }

    /** @return application/scope name the stream was published under, or null. */
    public String originApp(String streamId) {
        String value = map().get(streamId);
        String[] parts = value != null ? value.split("\\|", 2) : new String[0];
        return parts.length == 2 ? parts[1] : null;
    }

    public Set<String> streamsOnNode(String nodeId) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, String> entry : map().readAllMap().entrySet()) {
            if (entry.getValue().split("\\|", 2)[0].equals(nodeId)) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    public Map<String, String> all() {
        return map().readAllMap();
    }
}
