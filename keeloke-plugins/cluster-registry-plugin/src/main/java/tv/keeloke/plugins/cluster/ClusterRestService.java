package tv.keeloke.plugins.cluster;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * GET /keeloke/v1/cluster/nodes -> currently-alive Keeloke TV Server nodes
 * sharing the same Redis registry, with their live-stream load. Useful as
 * the data source for an external load balancer / ingest router.
 */
@RestController
@RequestMapping("/keeloke/v1/cluster")
public class ClusterRestService {

    @Autowired
    private ClusterRegistryPlugin clusterRegistryPlugin;

    @GetMapping("/nodes")
    public Collection<ClusterNodeInfo> nodes() {
        return clusterRegistryPlugin.listNodes().values();
    }
}
