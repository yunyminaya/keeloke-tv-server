package tv.keeloke.plugins.security;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-tenant (per Ant Media "application") security configuration, shared
 * cluster-wide via Redis so every node in a multi-node deployment enforces
 * the same rules.
 */
public class TenantSecurityConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tenantApp;

    /** HMAC secret used to issue/verify per-stream tokens. Null/blank = token check disabled for this tenant. */
    private String tokenSecret;

    /** TOTP shared secret (base32) protecting this tenant's admin REST calls. Null/blank = TOTP not required. */
    private String totpSecret;

    /** If true, isPublishAllowed/isPlayAllowed require a valid token when tokenSecret is set. */
    private boolean requireTokenForPublish = false;
    private boolean requireTokenForPlay = false;

    /** CIDR allow-list; empty = allow from anywhere (subject to denyList). */
    private List<String> ipAllowList = new ArrayList<>();

    /** CIDR deny-list; checked before allow-list. */
    private List<String> ipDenyList = new ArrayList<>();

    /** ISO-3166-1 alpha-2 country codes allowed to play. Empty = no geo restriction. */
    private List<String> countryAllowList = new ArrayList<>();

    /** Webhook endpoint(s) receiving granular events for this tenant. */
    private List<String> webhookUrls = new ArrayList<>();

    /** Shared secret used to HMAC-sign the webhook payload (X-Keeloke-Signature header). */
    private String webhookSecret;

    public TenantSecurityConfig() {
    }

    public TenantSecurityConfig(String tenantApp) {
        this.tenantApp = tenantApp;
    }

    public String getTenantApp() { return tenantApp; }
    public void setTenantApp(String tenantApp) { this.tenantApp = tenantApp; }

    public String getTokenSecret() { return tokenSecret; }
    public void setTokenSecret(String tokenSecret) { this.tokenSecret = tokenSecret; }

    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }

    public boolean isRequireTokenForPublish() { return requireTokenForPublish; }
    public void setRequireTokenForPublish(boolean requireTokenForPublish) { this.requireTokenForPublish = requireTokenForPublish; }

    public boolean isRequireTokenForPlay() { return requireTokenForPlay; }
    public void setRequireTokenForPlay(boolean requireTokenForPlay) { this.requireTokenForPlay = requireTokenForPlay; }

    public List<String> getIpAllowList() { return ipAllowList; }
    public void setIpAllowList(List<String> ipAllowList) { this.ipAllowList = ipAllowList; }

    public List<String> getIpDenyList() { return ipDenyList; }
    public void setIpDenyList(List<String> ipDenyList) { this.ipDenyList = ipDenyList; }

    public List<String> getCountryAllowList() { return countryAllowList; }
    public void setCountryAllowList(List<String> countryAllowList) { this.countryAllowList = countryAllowList; }

    public List<String> getWebhookUrls() { return webhookUrls; }
    public void setWebhookUrls(List<String> webhookUrls) { this.webhookUrls = webhookUrls; }

    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
