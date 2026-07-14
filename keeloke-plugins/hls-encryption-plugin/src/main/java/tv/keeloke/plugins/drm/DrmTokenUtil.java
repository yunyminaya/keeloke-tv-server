package tv.keeloke.plugins.drm;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Signed token gating key delivery: a player may fetch a stream's AES key only
 * with a valid token. Same HMAC-SHA256 construction as the security plugin's
 * stream tokens, kept independent so this plugin has no cross-module dependency.
 * Format: {@code <base64url(streamId:expiry)>.<base64url(HMAC)>}
 */
public final class DrmTokenUtil {

    private DrmTokenUtil() {
    }

    public static String issue(String secret, String streamId, long ttlSeconds) {
        long expiry = System.currentTimeMillis() / 1000L + ttlSeconds;
        String payload = streamId + ":" + expiry;
        return b64(payload) + "." + sign(secret, payload);
    }

    public static boolean verify(String secret, String streamId, String token) {
        if (secret == null || secret.isBlank() || token == null || !token.contains(".")) {
            return false;
        }
        String[] parts = token.split("\\.", 2);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (!constantTimeEquals(sign(secret, payload), parts[1])) {
            return false;
        }
        String[] p = payload.split(":", 2);
        if (p.length != 2 || !p[0].equals(streamId)) {
            return false;
        }
        try {
            return System.currentTimeMillis() / 1000L <= Long.parseLong(p[1]);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String sign(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to sign DRM token", e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
