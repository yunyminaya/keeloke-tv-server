package tv.keeloke.plugins.security;

import org.springframework.context.ApplicationContext;
import org.springframework.web.context.support.WebApplicationContextUtils;

import jakarta.servlet.ServletContext;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the security &amp; analytics plugin. Mirrors the
 * JAX-RS/Jersey + WebApplicationContextUtils pattern already used by
 * tv.keeloke.plugins.tenant.TenantRestService (Spring MVC annotations are
 * inert on the server's REST layer - see that class's javadoc).
 *
 * Admin-mutating endpoints (marked below) require a valid TOTP code in the
 * X-Keeloke-Totp-Code header whenever the tenant has a totpSecret configured
 * - if no TOTP secret is set yet for a tenant, those endpoints are open,
 * matching how you'd bootstrap the very first admin action (set the TOTP
 * secret itself) before TOTP protection can apply.
 */
@Path("/keeloke/v1/security")
public class SecurityRestService {

    @Context
    private ServletContext servletContext;

    private SecurityConfigStore configStore() {
        return ctx().getBean(SecurityConfigStore.class);
    }

    private WebhookDispatcher webhookDispatcher() {
        return ctx().getBean(WebhookDispatcher.class);
    }

    private ViewerAnalyticsPlugin analytics() {
        return ctx().getBean(ViewerAnalyticsPlugin.class);
    }

    private ApplicationContext ctx() {
        return WebApplicationContextUtils.getWebApplicationContext(servletContext);
    }

    // ---- Tenant security config ----

    @GET
    @Path("/{app}/config")
    @Produces(MediaType.APPLICATION_JSON)
    public TenantSecurityConfig getConfig(@PathParam("app") String app) {
        return configStore().get(app);
    }

    @POST
    @Path("/{app}/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveConfig(@PathParam("app") String app,
                                @HeaderParam("X-Keeloke-Totp-Code") String totpCode,
                                TenantSecurityConfig incoming) {
        TenantSecurityConfig existing = configStore().get(app);
        if (!totpAllows(existing, totpCode)) {
            return Response.status(Response.Status.FORBIDDEN).entity("invalid or missing TOTP code").build();
        }
        incoming.setTenantApp(app);
        configStore().save(incoming);
        return Response.ok().build();
    }

    // ---- TOTP provisioning ----

    @POST
    @Path("/{app}/totp/provision")
    @Produces(MediaType.TEXT_PLAIN)
    public String provisionTotp(@PathParam("app") String app) {
        TenantSecurityConfig cfg = configStore().get(app);
        String secret = TotpUtil.generateBase32Secret();
        cfg.setTotpSecret(secret);
        configStore().save(cfg);
        return secret; // caller enrolls this base32 secret in an authenticator app (Google Authenticator, Authy, ...)
    }

    @GET
    @Path("/{app}/totp/verify")
    @Produces(MediaType.TEXT_PLAIN)
    public Response verifyTotp(@PathParam("app") String app, @QueryParam("code") String code) {
        TenantSecurityConfig cfg = configStore().get(app);
        boolean valid = TotpUtil.verifyCode(cfg.getTotpSecret(), code, 1);
        return valid ? Response.ok("valid").build() : Response.status(Response.Status.UNAUTHORIZED).entity("invalid").build();
    }

    // ---- Stream tokens ----

    @POST
    @Path("/{app}/streams/{streamId}/token")
    @Produces(MediaType.TEXT_PLAIN)
    public Response issueToken(@PathParam("app") String app, @PathParam("streamId") String streamId,
                                @QueryParam("ttlSeconds") @DefaultValue("3600") long ttlSeconds,
                                @HeaderParam("X-Keeloke-Totp-Code") String totpCode) {
        TenantSecurityConfig cfg = configStore().get(app);
        if (!totpAllows(cfg, totpCode)) {
            return Response.status(Response.Status.FORBIDDEN).entity("invalid or missing TOTP code").build();
        }
        if (cfg.getTokenSecret() == null || cfg.getTokenSecret().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity("tenant has no tokenSecret configured").build();
        }
        return Response.ok(StreamTokenUtil.issue(cfg.getTokenSecret(), streamId, ttlSeconds)).build();
    }

    // ---- IP rules (convenience mutators on top of /config) ----

    @POST
    @Path("/{app}/ip-allow")
    public Response addIpAllow(@PathParam("app") String app, @QueryParam("cidr") String cidr,
                                @HeaderParam("X-Keeloke-Totp-Code") String totpCode) {
        TenantSecurityConfig cfg = configStore().get(app);
        if (!totpAllows(cfg, totpCode)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        cfg.getIpAllowList().add(cidr);
        configStore().save(cfg);
        return Response.ok().build();
    }

    @POST
    @Path("/{app}/ip-deny")
    public Response addIpDeny(@PathParam("app") String app, @QueryParam("cidr") String cidr,
                               @HeaderParam("X-Keeloke-Totp-Code") String totpCode) {
        TenantSecurityConfig cfg = configStore().get(app);
        if (!totpAllows(cfg, totpCode)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        cfg.getIpDenyList().add(cidr);
        configStore().save(cfg);
        return Response.ok().build();
    }

    // ---- Analytics ----

    @GET
    @Path("/streams/{streamId}/analytics")
    @Produces(MediaType.APPLICATION_JSON)
    public StreamAnalytics streamAnalytics(@PathParam("streamId") String streamId) {
        return analytics().snapshot(streamId);
    }

    @POST
    @Path("/streams/{streamId}/heartbeat")
    public void heartbeat(@PathParam("streamId") String streamId, @QueryParam("sessionId") String sessionId) {
        analytics().heartbeat(streamId, sessionId);
    }

    @POST
    @Path("/{app}/streams/{streamId}/leave")
    public void leave(@PathParam("app") String app, @PathParam("streamId") String streamId,
                       @QueryParam("sessionId") String sessionId) {
        analytics().recordLeave(app, streamId, sessionId);
    }

    // ---- Webhook test ----

    @POST
    @Path("/{app}/webhooks/test")
    public void testWebhook(@PathParam("app") String app) {
        webhookDispatcher().dispatch(app, "webhook.test", Map.of("message", "Keeloke TV Server webhook test"));
    }

    private boolean totpAllows(TenantSecurityConfig cfg, String submittedCode) {
        if (cfg.getTotpSecret() == null || cfg.getTotpSecret().isBlank()) {
            return true; // no TOTP configured yet for this tenant - open until bootstrapped
        }
        return TotpUtil.verifyCode(cfg.getTotpSecret(), submittedCode, 1);
    }
}
