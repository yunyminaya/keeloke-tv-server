package tv.keeloke.plugins.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fires granular, per-tenant, HMAC-signed webhook events beyond the server's
 * built-in AppSettings.listenerHookURL (which only covers
 * stream started/finished/VOD-ready). Adds finer events - access denials,
 * viewer join/leave, quota rejections, etc. - to one or more configurable
 * URLs per tenant, delivered asynchronously with bounded retry so a slow or
 * down webhook receiver can never block the media pipeline.
 *
 * Payload is signed the way most webhook consumers (Stripe, GitHub, etc.)
 * expect: header X-Keeloke-Signature = hex(HMAC-SHA256(rawBody, tenant's
 * webhookSecret)), so receivers can verify authenticity without a shared TLS
 * client-cert setup.
 */
@Component
public class WebhookDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private SecurityConfigStore configStore;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "keeloke-webhook-dispatch");
        t.setDaemon(true);
        return t;
    });

    public void dispatch(String tenantApp, String eventType, Map<String, Object> data) {
        TenantSecurityConfig cfg = configStore.get(tenantApp);
        if (cfg.getWebhookUrls().isEmpty()) {
            return;
        }
        String body = toJson(tenantApp, eventType, data);
        String signature = cfg.getWebhookSecret() != null && !cfg.getWebhookSecret().isBlank()
                ? sign(cfg.getWebhookSecret(), body)
                : null;

        for (String url : cfg.getWebhookUrls()) {
            executor.submit(() -> sendWithRetry(url, body, signature));
        }
    }

    private void sendWithRetry(String url, String body, String signature) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (signature != null) {
            builder.header("X-Keeloke-Signature", signature);
        }
        HttpRequest request = builder.build();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 300) {
                    return;
                }
                logger.warn("Webhook {} responded {} (attempt {}/{})", url, response.statusCode(), attempt, MAX_ATTEMPTS);
            } catch (Exception e) {
                logger.warn("Webhook {} failed (attempt {}/{}): {}", url, attempt, MAX_ATTEMPTS, e.getMessage());
            }
            try {
                Thread.sleep(500L * attempt);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.error("Webhook {} gave up after {} attempts", url, MAX_ATTEMPTS);
    }

    private String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign webhook payload", e);
        }
    }

    private String toJson(String tenantApp, String eventType, Map<String, Object> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"tenant\":\"").append(escape(tenantApp)).append("\",");
        sb.append("\"event\":\"").append(escape(eventType)).append("\",");
        sb.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        sb.append("\"data\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":\"")
                    .append(escape(String.valueOf(entry.getValue()))).append("\"");
        }
        sb.append("}}");
        return sb.toString();
    }

    private String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
