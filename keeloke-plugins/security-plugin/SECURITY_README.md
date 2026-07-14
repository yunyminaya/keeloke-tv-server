# Keeloke TV Server - Security & Analytics Plugin

No paid Enterprise license required. This is original code written for
Keeloke TV Server; it does not use, embed, or depend on any proprietary
Enterprise artifact.

## What it adds over Community Edition

| Feature | How |
|---|---|
| Token-based auth per stream (publish and/or play) | HMAC-SHA256 signed tokens with expiry, issued per tenant via REST (`StreamTokenUtil`) |
| IP filtering | CIDR allow/deny lists per tenant, enforced on every publish/play attempt (`CidrMatcher`) |
| TOTP (RFC 6238) | Protects tenant admin REST calls (config changes, token issuance) with a standard 6-digit authenticator-app code (`TotpUtil`) - no external dependency |
| Geo-restriction | Country allow-list enforcement point is live; actual IP→country resolution is pluggable (`GeoIpResolver`) and ships as a no-op ("XX" = unknown) - see below for why |
| Granular webhooks | `access.denied`, `viewer.joined`, `viewer.left`, `webhook.test` events, HMAC-signed, async with retry, independent of `AppSettings.listenerHookURL` |
| Viewer analytics | Per-stream play-session count, concurrent-viewer estimate (heartbeat/TTL based), viewer country breakdown, exposed via REST |

## Why geo-restriction ships as a no-op by default

Real IP→country resolution needs a geo database. The common free option,
MaxMind GeoLite2, requires **you** to create your own free MaxMind account
and accept their license to download `GeoLite2-Country.mmdb` - that's a
registration decision only you can make, so this plugin doesn't bundle it or
fake results. `GeoIpResolver` is a one-method interface
(`countryOf(String ip)`); once you have an `.mmdb` file, implementing a
MaxMind-backed resolver is a handful of lines against the
`com.maxmind.geoip2:geoip2` library. Until then, country allow-lists exist
and are enforced, but every IP resolves to `"XX"` (unknown) so nothing gets
silently misclassified as a country it isn't.

## Why concurrent-viewer count is heartbeat-based

The Red5 core doesn't expose one reliable "playback stopped" event that
fires consistently across RTMP, HLS and WebRTC. This plugin counts a viewer
present via a TTL key (45s) refreshed by a heartbeat call from the player;
miss enough heartbeats and the viewer ages out automatically. This is the
same practical tradeoff most viewer-count systems make - it's an estimate
converging within ~45s of reality, not an exact real-time counter.

## REST API (mounted at `/rest/keeloke/v1/security`)

```
GET  /{app}/config                                  -> current TenantSecurityConfig
POST /{app}/config                                   (X-Keeloke-Totp-Code)  -> replace TenantSecurityConfig
POST /{app}/totp/provision                           -> generates+stores a TOTP secret, returns base32 to enroll
GET  /{app}/totp/verify?code=123456                  -> checks a code against the tenant's secret
POST /{app}/streams/{streamId}/token?ttlSeconds=3600  (X-Keeloke-Totp-Code)  -> issues a signed play/publish token
POST /{app}/ip-allow?cidr=203.0.113.0/24              (X-Keeloke-Totp-Code)
POST /{app}/ip-deny?cidr=198.51.100.4/32              (X-Keeloke-Totp-Code)
GET  /streams/{streamId}/analytics                    -> StreamAnalytics snapshot
POST /streams/{streamId}/heartbeat?sessionId=...       -> keep a viewer counted as present
POST /{app}/streams/{streamId}/leave?sessionId=...     -> explicit clean leave
POST /{app}/webhooks/test                             -> fires a webhook.test event
```

## Enabling enforcement for a tenant

By default a tenant (application) has no rules, so nothing is enforced.
To turn it on:

```bash
curl -X POST http://SERVER:5080/LiveApp/rest/keeloke/v1/security/LiveApp/config \
  -H "Content-Type: application/json" \
  -d '{
    "tenantApp": "LiveApp",
    "tokenSecret": "replace-with-a-long-random-secret",
    "requireTokenForPlay": true,
    "ipDenyList": ["203.0.113.0/24"],
    "webhookUrls": ["https://your-backend.example.com/keeloke-events"],
    "webhookSecret": "another-long-random-secret"
  }'
```

Then issue a per-viewer token:

```bash
curl -X POST "http://SERVER:5080/LiveApp/rest/keeloke/v1/security/LiveApp/streams/myStream/token?ttlSeconds=600"
```

and pass it back as `?token=...` (or the `token` query param the server's
players already support) when playing `myStream`.

## Configuration properties (application.properties / red5.properties)

```
keeloke.security.redisAddress=redis://127.0.0.1:6379
```

Reuses the same Redis instance as `tenant-quota-plugin` and
`cluster-registry-plugin` so rules are shared cluster-wide out of the box.
