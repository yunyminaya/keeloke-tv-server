package tv.keeloke.plugins.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * Self-contained RFC 6238 (TOTP) implementation - no external dependency.
 * 30-second time step, 6-digit codes, HMAC-SHA1 (the widely compatible
 * default used by Google Authenticator / Authy / 1Password).
 */
public final class TotpUtil {

    private static final int TIME_STEP_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpUtil() {
    }

    public static String generateBase32Secret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static String currentCode(String base32Secret) {
        return codeAt(base32Secret, System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS);
    }

    /**
     * Verifies a submitted code, tolerating clock drift of +/- allowedSteps
     * time-steps (default usage: allowedSteps=1 -> accepts codes up to 30s
     * old or new, which is the standard TOTP UX tradeoff).
     */
    public static boolean verifyCode(String base32Secret, String submittedCode, int allowedSteps) {
        if (base32Secret == null || base32Secret.isBlank() || submittedCode == null) {
            return false;
        }
        long currentStep = System.currentTimeMillis() / 1000L / TIME_STEP_SECONDS;
        for (int i = -allowedSteps; i <= allowedSteps; i++) {
            if (codeAt(base32Secret, currentStep + i).equals(submittedCode.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String codeAt(String base32Secret, long timeStep) {
        byte[] key = base32Decode(base32Secret);
        byte[] data = new byte[8];
        long value = timeStep;
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (value & 0xFF);
            value >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            int otp = binary % (int) Math.pow(10, CODE_DIGITS);
            return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute TOTP code", e);
        }
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((value >>> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String base32) {
        String clean = base32.trim().toUpperCase(Locale.ROOT).replace("=", "");
        int bits = 0, value = 0, index = 0;
        byte[] output = new byte[clean.length() * 5 / 8];
        for (int i = 0; i < clean.length(); i++) {
            int charIndex = BASE32_ALPHABET.indexOf(clean.charAt(i));
            if (charIndex < 0) {
                continue;
            }
            value = (value << 5) | charIndex;
            bits += 5;
            if (bits >= 8) {
                output[index++] = (byte) ((value >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return output;
    }
}
