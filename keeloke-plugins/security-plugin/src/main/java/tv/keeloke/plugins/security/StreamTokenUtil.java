package tv.keeloke.plugins.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Per-stream signed tokens, independent of the server's built-in JWT/hash
 * token service so it can be enabled per-tenant without touching global
 * AppSettings. Format: {@code <base64url(streamId:expiryEpochSeconds)>.<base64url(HMAC-SHA256)>}
 */
public final class StreamTokenUtil {

    private StreamTokenUtil() {
    }

    public static String issue(String tenantSecret, String streamId, long ttlSeconds) {
        long expiry = System.currentTimeMillis() / 1000L + ttlSeconds;
        String payload = streamId + ":" + expiry;
        String signature = sign(tenantSecret, payload);
        return b64(payload) + "." + signature;
    }

    public static boolean verify(String tenantSecret, String streamId, String token) {
        if (tenantSecret == null || tenantSecret.isBlank() || token == null || !token.contains(".")) {
            return false;
        }
        String[] parts = token.split("\\.", 2);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String expectedSignature = sign(tenantSecret, payload);
        if (!constantTimeEquals(expectedSignature, parts[1])) {
            return false;
        }
        String[] payloadParts = payload.split(":", 2);
        if (payloadParts.length != 2 || !payloadParts[0].equals(streamId)) {
            return false;
        }
        long expiry;
        try {
            expiry = Long.parseLong(payloadParts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        return System.currentTimeMillis() / 1000L <= expiry;
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign stream token", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
