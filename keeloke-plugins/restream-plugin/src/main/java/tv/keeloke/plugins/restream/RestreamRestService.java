package tv.keeloke.plugins.restream;

import io.antmedia.rest.model.Result;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * REST API to manage restream destinations, e.g.:
 *
 *   POST /rest/keeloke/v1/restream/destinations
 *   { "streamId": "stream1", "name": "YouTube", "rtmpUrl": "rtmp://a.rtmp.youtube.com/live2/xxxx-xxxx-xxxx-xxxx" }
 *
 *   GET    /rest/keeloke/v1/restream/destinations?streamId=stream1
 *   DELETE /rest/keeloke/v1/restream/destinations/{id}?streamId=stream1
 *
 * A destination added while the stream is already live is started immediately;
 * one added before the stream starts is picked up automatically on streamStarted().
 *
 * JAX-RS with manual Spring bean lookup via WebApplicationContextUtils -
 * see TenantRestService for why.
 */
@Path("/keeloke/v1/restream")
public class RestreamRestService {

    @Context
    private ServletContext servletContext;

    private RestreamPlugin plugin() {
        ApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        return ctx.getBean(RestreamPlugin.class);
    }

    private int rtmpPort() {
        return Integer.parseInt(System.getProperty("server.rtmp_port", "1935"));
    }

    @GET
    @Path("/destinations")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RestreamDestination> list(@QueryParam("streamId") String streamId) {
        if (streamId != null) {
            return plugin().getStore().forStream(streamId);
        }
        return plugin().getStore().all();
    }

    @POST
    @Path("/destinations")
    @Produces(MediaType.APPLICATION_JSON)
    public RestreamDestination add(@QueryParam("streamId") String streamId,
                                    @QueryParam("name") String name,
                                    @QueryParam("rtmpUrl") String rtmpUrl,
                                    @QueryParam("startNow") @DefaultValue("false") boolean startNow,
                                    @QueryParam("appName") @DefaultValue("live") String appName) {
        RestreamPlugin restreamPlugin = plugin();
        RestreamDestination destination = restreamPlugin.getStore().add(streamId, name, rtmpUrl);
        if (startNow) {
            String sourceUrl = "rtmp://127.0.0.1:" + rtmpPort() + "/" + appName + "/" + streamId;
            restreamPlugin.getProcessManager().start(sourceUrl, streamId, destination);
        }
        return destination;
    }

    @DELETE
    @Path("/destinations/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Result remove(@PathParam("id") String id, @QueryParam("streamId") String streamId) {
        RestreamPlugin restreamPlugin = plugin();
        restreamPlugin.getProcessManager().stop(streamId, id);
        boolean removed = restreamPlugin.getStore().remove(id);
        Result result = new Result(removed);
        result.setMessage(removed ? "Destination removed" : "Destination not found");
        return result;
    }
}
