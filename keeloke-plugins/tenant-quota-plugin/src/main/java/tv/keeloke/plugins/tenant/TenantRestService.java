package tv.keeloke.plugins.tenant;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.Collection;

/**
 * GET  /rest/keeloke/v1/tenants/usage           -> usage analytics for every tenant (app)
 * POST /rest/keeloke/v1/tenants/{app}/quota?maxConcurrentStreams=N -> override a tenant's quota
 *
 * Ant Media Server's REST layer is JAX-RS/Jersey (mapped at /rest/*, scanning
 * io.antmedia.rest by default), not Spring MVC - @RestController/@RequestMapping
 * are silently inert here (verified live: returned 404 for every path). Jersey
 * also instantiates its own resource objects rather than pulling them from the
 * Spring context, so plain @Autowired fields come back null (verified live: 500
 * NullPointerException). io.antmedia.rest.RestServiceBase's actual pattern -
 * confirmed via javap - is @Context ServletContext + WebApplicationContextUtils
 * to fetch the bean from Spring manually. Mirrored here.
 */
@Path("/keeloke/v1/tenants")
public class TenantRestService {

    @Context
    private ServletContext servletContext;

    private TenantQuotaPlugin plugin() {
        ApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        return ctx.getBean(TenantQuotaPlugin.class);
    }

    @GET
    @Path("/usage")
    @Produces(MediaType.APPLICATION_JSON)
    public Collection<TenantUsage> usage() {
        return plugin().allTenantUsage().values();
    }

    @POST
    @Path("/{app}/quota")
    public void setQuota(@PathParam("app") String app, @QueryParam("maxConcurrentStreams") int maxConcurrentStreams) {
        plugin().setQuotaForTenant(app, maxConcurrentStreams);
    }
}
