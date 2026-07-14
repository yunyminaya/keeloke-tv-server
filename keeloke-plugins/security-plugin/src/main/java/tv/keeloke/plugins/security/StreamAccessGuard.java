package tv.keeloke.plugins.security;

import org.red5.server.api.IConnection;
import org.red5.server.api.Red5;
import org.red5.server.api.scope.IScope;
import org.red5.server.api.stream.IStreamPlaybackSecurity;
import org.red5.server.api.stream.IStreamPublishSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Enforces, per tenant (Ant Media application):
 *  - token-based auth for publish and/or play (see {@link StreamTokenUtil})
 *  - IP allow/deny lists (CIDR, see {@link CidrMatcher})
 *  - country allow-list (best-effort, see {@link GeoIpResolver})
 *
 * Registered as both IStreamPublishSecurity and IStreamPlaybackSecurity beans
 * so Ant Media's Red5 core calls it on every publish/play attempt - same
 * extension points already used by io.antmedia.security.ExpireStreamPublishSecurity
 * and tv.keeloke.plugins.tenant.TenantQuotaPlugin.
 */
@Component
public class StreamAccessGuard implements IStreamPublishSecurity, IStreamPlaybackSecurity {

    private static final Logger logger = LoggerFactory.getLogger(StreamAccessGuard.class);

    @Autowired
    private SecurityConfigStore configStore;

    @Autowired
    private GeoIpResolver geoIpResolver;

    @Autowired
    private WebhookDispatcher webhookDispatcher;

    @Override
    public boolean isPublishAllowed(IScope scope, String name, String mode, Map<String, String> queryParams,
                                     String metaData, String token, String subscriberId, String subscriberCode) {
        String tenantApp = scope.getName();
        TenantSecurityConfig cfg = configStore.get(tenantApp);

        if (!ipAllowed(cfg, tenantApp, name, "publish")) {
            return false;
        }
        if (cfg.isRequireTokenForPublish() && !tokenAllowed(cfg, name, token, queryParams, tenantApp, "publish")) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isPlaybackAllowed(IScope scope, String name, int start, int length, boolean flushPlaylist) {
        return true; // legacy hook kept permissive; isPlayAllowed below is the real enforcement point (has token/IP context)
    }

    @Override
    public boolean isPlayAllowed(IScope scope, String name, String mode, Map<String, String> queryParams,
                                  String metaData, String token, String subscriberId, String subscriberCode) {
        String tenantApp = scope.getName();
        TenantSecurityConfig cfg = configStore.get(tenantApp);

        if (!ipAllowed(cfg, tenantApp, name, "play")) {
            return false;
        }
        if (!countryAllowed(cfg, tenantApp, name)) {
            return false;
        }
        if (cfg.isRequireTokenForPlay() && !tokenAllowed(cfg, name, token, queryParams, tenantApp, "play")) {
            return false;
        }
        return true;
    }

    private boolean ipAllowed(TenantSecurityConfig cfg, String tenantApp, String streamId, String action) {
        String remoteIp = remoteIp();
        if (remoteIp == null) {
            return true; // no connection context (e.g. internal call) - nothing to check
        }
        if (!cfg.getIpDenyList().isEmpty() && CidrMatcher.matchesAny(remoteIp, cfg.getIpDenyList())) {
            logger.warn("Rejecting {} of '{}' on tenant '{}': IP {} is denylisted", action, streamId, tenantApp, remoteIp);
            webhookDispatcher.dispatch(tenantApp, "access.denied", Map.of(
                    "streamId", streamId, "action", action, "reason", "ip_denylisted", "ip", remoteIp));
            return false;
        }
        if (!cfg.getIpAllowList().isEmpty() && !CidrMatcher.matchesAny(remoteIp, cfg.getIpAllowList())) {
            logger.warn("Rejecting {} of '{}' on tenant '{}': IP {} not in allowlist", action, streamId, tenantApp, remoteIp);
            webhookDispatcher.dispatch(tenantApp, "access.denied", Map.of(
                    "streamId", streamId, "action", action, "reason", "ip_not_allowlisted", "ip", remoteIp));
            return false;
        }
        return true;
    }

    private boolean countryAllowed(TenantSecurityConfig cfg, String tenantApp, String streamId) {
        if (cfg.getCountryAllowList().isEmpty()) {
            return true;
        }
        String remoteIp = remoteIp();
        if (remoteIp == null) {
            return true;
        }
        String country = geoIpResolver.countryOf(remoteIp);
        if (!cfg.getCountryAllowList().contains(country)) {
            logger.warn("Rejecting play of '{}' on tenant '{}': country {} not allowed", streamId, tenantApp, country);
            webhookDispatcher.dispatch(tenantApp, "access.denied", Map.of(
                    "streamId", streamId, "action", "play", "reason", "country_not_allowed", "country", country));
            return false;
        }
        return true;
    }

    private boolean tokenAllowed(TenantSecurityConfig cfg, String streamId, String token,
                                  Map<String, String> queryParams, String tenantApp, String action) {
        String candidate = token;
        if ((candidate == null || candidate.isBlank()) && queryParams != null) {
            candidate = queryParams.get("token");
        }
        boolean valid = StreamTokenUtil.verify(cfg.getTokenSecret(), streamId, candidate);
        if (!valid) {
            logger.warn("Rejecting {} of '{}' on tenant '{}': invalid or missing token", action, streamId, tenantApp);
            webhookDispatcher.dispatch(tenantApp, "access.denied", Map.of(
                    "streamId", streamId, "action", action, "reason", "invalid_token"));
        }
        return valid;
    }

    private String remoteIp() {
        IConnection connection = Red5.getConnectionLocal();
        return connection != null ? connection.getRemoteAddress() : null;
    }
}
