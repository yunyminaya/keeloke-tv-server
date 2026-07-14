package tv.keeloke.plugins.drm;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Key delivery + token issuance for AES-128 HLS.
 *
 *   GET  /keeloke/v1/drm/{streamId}/key?token=...   -> raw 16-byte AES key (application/octet-stream), token-gated
 *   POST /keeloke/v1/drm/{streamId}/token?ttl=...    -> issue a delivery token (for your own player/backend)
 *   GET  /keeloke/v1/drm/{streamId}/status           -> whether the stream is encrypted
 */
@Path("/keeloke/v1/drm")
public class DrmRestService {

    @Context
    private ServletContext servletContext;

    private ApplicationContext ctx() {
        return WebApplicationContextUtils.getWebApplicationContext(servletContext);
    }

    private HlsKeyStore keyStore() {
        return ctx().getBean(HlsKeyStore.class);
    }

    private HlsEncryptionConfig config() {
        return ctx().getBean(HlsEncryptionConfig.class);
    }

    @GET
    @Path("/{streamId}/key")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getKey(@PathParam("streamId") String streamId, @QueryParam("token") String token) {
        HlsEncryptionConfig cfg = config();
        String secret = cfg.getKeySecret();

        // if a secret is configured, the token must be valid; if not, delivery is open (documented)
        if (secret != null && !secret.isBlank() && !DrmTokenUtil.verify(secret, streamId, token)) {
            return Response.status(Response.Status.FORBIDDEN).entity("invalid or missing DRM token").build();
        }
        byte[] key = keyStore().get(streamId);
        if (key == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("stream not encrypted / key expired").build();
        }
        return Response.ok(key).build();
    }

    @POST
    @Path("/{streamId}/token")
    @Produces(MediaType.TEXT_PLAIN)
    public Response issueToken(@PathParam("streamId") String streamId,
                               @QueryParam("ttl") @DefaultValue("3600") long ttl) {
        HlsEncryptionConfig cfg = config();
        if (cfg.getKeySecret() == null || cfg.getKeySecret().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("no keeloke.drm.keySecret configured; key delivery is open").build();
        }
        return Response.ok(DrmTokenUtil.issue(cfg.getKeySecret(), streamId, ttl)).build();
    }

    @GET
    @Path("/{streamId}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("streamId") String streamId) {
        boolean enc = keyStore().isEncrypted(streamId);
        return Response.ok("{\"streamId\":\"" + streamId.replace("\"", "") + "\",\"encrypted\":" + enc + "}").build();
    }
}
