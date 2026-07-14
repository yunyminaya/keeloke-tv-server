package tv.keeloke.plugins.spatial;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 *   POST /keeloke/v1/spatial/{streamId}/mark360     -> tag a stream so its recording gets 360 metadata
 *   POST /keeloke/v1/spatial/{streamId}/unmark360
 *   GET  /keeloke/v1/spatial/{streamId}/status      -> whether it's treated as 360
 */
@Path("/keeloke/v1/spatial")
public class Spatial360RestService {

    @Context
    private ServletContext servletContext;

    private Spatial360Plugin plugin() {
        ApplicationContext ctx = WebApplicationContextUtils.getWebApplicationContext(servletContext);
        return ctx.getBean(Spatial360Plugin.class);
    }

    @POST
    @Path("/{streamId}/mark360")
    public void mark(@PathParam("streamId") String streamId) {
        plugin().mark360(streamId);
    }

    @POST
    @Path("/{streamId}/unmark360")
    public void unmark(@PathParam("streamId") String streamId) {
        plugin().unmark360(streamId);
    }

    @GET
    @Path("/{streamId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("streamId") String streamId) {
        return Response.ok("{\"streamId\":\"" + streamId.replace("\"", "") + "\",\"is360\":" + plugin().is360(streamId) + "}").build();
    }
}
