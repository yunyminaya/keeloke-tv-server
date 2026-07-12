package tv.keeloke.plugins.cluster;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;

/**
 * GET /rest/keeloke/v1/cluster/nodes -> currently-alive Keeloke TV Server nodes
 * sharing the same Redis registry, with their live-stream load.
 *
 * JAX-RS with manual Spring bean lookup via WebApplicationContextUtils -
 * see TenantRestService for why (plain @Autowired/@Component came back null
 * live, since Jersey instantiates its own resources).
 */
@Path("/keeloke/v1/cluster")
public class ClusterRestService {

    @Context
    private ServletContext servletContext;

    @GET
    @Path("/nodes")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<ClusterNodeInfo> nodes() {
        ApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        return ctx.getBean(ClusterRegistryPlugin.class).listNodes().values();
    }
}
