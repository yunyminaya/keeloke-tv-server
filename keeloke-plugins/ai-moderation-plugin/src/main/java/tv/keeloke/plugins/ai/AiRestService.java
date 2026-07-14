package tv.keeloke.plugins.ai;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST surface for AI moderation. Manual Spring bean lookup via
 * WebApplicationContextUtils (same pattern as the other Keeloke plugins).
 *
 *   GET  /keeloke/v1/ai/config                       -> current config (apiKey redacted)
 *   POST /keeloke/v1/ai/config                        -> replace config
 *   GET  /keeloke/v1/ai/streams/{streamId}/last       -> last moderation result for a stream
 *   POST /keeloke/v1/ai/streams/{streamId}/analyze    -> grab one frame now and analyze it
 */
@Path("/keeloke/v1/ai")
public class AiRestService {

    @Context
    private ServletContext servletContext;

    private ApplicationContext ctx() {
        return WebApplicationContextUtils.getWebApplicationContext(servletContext);
    }

    private AiConfigStore configStore() {
        return ctx().getBean(AiConfigStore.class);
    }

    private AiModerationPlugin plugin() {
        return ctx().getBean(AiModerationPlugin.class);
    }

    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    public AiModerationConfig getConfig() {
        AiModerationConfig cfg = configStore().get();
        // redact the key in responses
        AiModerationConfig safe = cfg;
        if (cfg.getApiKey() != null && !cfg.getApiKey().isBlank()) {
            safe.setApiKey("***redacted***");
        }
        return safe;
    }

    @POST
    @Path("/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveConfig(AiModerationConfig incoming) {
        // if the caller sent the redaction placeholder back, keep the stored key
        if (incoming.getApiKey() == null || incoming.getApiKey().isBlank() || "***redacted***".equals(incoming.getApiKey())) {
            incoming.setApiKey(configStore().get().getApiKey());
        }
        configStore().save(incoming);
        return Response.ok().build();
    }

    @GET
    @Path("/streams/{streamId}/last")
    @Produces(MediaType.APPLICATION_JSON)
    public Response last(@PathParam("streamId") String streamId) {
        ModerationResult result = plugin().lastResult(streamId);
        if (result == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"no analysis yet for " + streamId + "\"}").build();
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/streams/{streamId}/analyze")
    @Produces(MediaType.APPLICATION_JSON)
    public Response analyzeNow(@PathParam("streamId") String streamId) {
        ModerationResult result = plugin().analyzeOnce(streamId);
        if (result == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity("{\"error\":\"could not grab a frame (is the stream live?)\"}").build();
        }
        return Response.ok(result).build();
    }
}
