package tv.keeloke.plugins.cluster;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cluster coordination REST surface.
 *
 * JAX-RS with manual Spring bean lookup via WebApplicationContextUtils -
 * see TenantRestService for why (plain @Autowired/@Component came back null
 * live, since Jersey instantiates its own resources).
 *
 *   GET /nodes                              -> alive nodes + load
 *   GET /best-origin?region=&#8230;             -> least-loaded node to publish to
 *   GET /origin/{streamId}                  -> origin node + edge pull URL for playback
 *   GET /scale                              -> JSON scale recommendation
 *   GET /metrics                            -> Prometheus text for HPA/ASG
 */
@Path("/keeloke/v1/cluster")
public class ClusterRestService {

    @Context
    private ServletContext servletContext;

    private ApplicationContext ctx() {
        return WebApplicationContextUtils.getWebApplicationContext(servletContext);
    }

    @GET
    @Path("/nodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<ClusterNodeInfo> nodes() {
        return ctx().getBean(ClusterRegistryPlugin.class).listNodes().values();
    }

    @GET
    @Path("/best-origin")
    @Produces(MediaType.APPLICATION_JSON)
    public Response bestOrigin(@QueryParam("region") String region) {
        Optional<ClusterNodeInfo> node = ctx().getBean(ClusterLoadBalancer.class).selectPublishNode(region);
        return node.<Response>map(n -> Response.ok(n).build())
                .orElse(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                        .entity("{\"error\":\"no node can accept a publish (cluster full or no origin/hybrid nodes alive)\"}")
                        .build());
    }

    @GET
    @Path("/origin/{streamId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response origin(@PathParam("streamId") String streamId) {
        ClusterLoadBalancer lb = ctx().getBean(ClusterLoadBalancer.class);
        Optional<ClusterNodeInfo> node = lb.resolvePlaybackOrigin(streamId);
        if (node.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"no live origin for streamId " + streamId + "\"}").build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("originNode", node.get());
        body.put("pullUrl", lb.originPullUrl(streamId));
        return Response.ok(body).build();
    }

    @GET
    @Path("/scale")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> scale() {
        ClusterAutoScaleAdvisor advisor = ctx().getBean(ClusterAutoScaleAdvisor.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recommendation", advisor.recommend().name());
        body.put("averageLoad", advisor.clusterAverageLoad());
        body.put("aliveNodes", advisor.aliveNodeCount());
        body.put("totalActiveStreams", advisor.totalActiveStreams());
        body.put("desiredNodeCount", advisor.desiredNodeCount());
        return body;
    }

    @GET
    @Path("/metrics")
    @Produces(MediaType.TEXT_PLAIN)
    public String metrics() {
        return ctx().getBean(ClusterAutoScaleAdvisor.class).prometheusMetrics();
    }
}
