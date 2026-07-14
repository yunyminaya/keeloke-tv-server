package tv.keeloke.plugins.drm;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * AES-128 key + IV generation and the FFmpeg "key info file" builder used to
 * produce encrypted HLS. This is real, standard HLS AES-128 content protection
 * (RFC 8216 §5) - "basic DRM": every .ts segment is AES-128-CBC encrypted and
 * the playlist points players at a key-delivery URL we gate with a token. It is
 * NOT a studio-certified DRM (Widevine/PlayReady/FairPlay) - those need a
 * licensed CDM/packager - but it stops casual segment ripping and unauthorized
 * playback, which is what "basic DRM" means.
 */
public final class HlsKeyUtil {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private HlsKeyUtil() {
    }

    /** 16 random bytes = one AES-128 key. */
    public static byte[] generateKey() {
        byte[] key = new byte[16];
        RANDOM.nextBytes(key);
        return key;
    }

    /** 16 random bytes = one initialization vector, as a 32-char hex string (FFmpeg keyinfo format). */
    public static String generateIvHex() {
        byte[] iv = new byte[16];
        RANDOM.nextBytes(iv);
        return HEX.formatHex(iv);
    }

    public static String toHex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }

    public static byte[] fromHex(String hex) {
        return HEX.parseHex(hex);
    }

    /**
     * Builds the 3-line FFmpeg "-hls_key_info_file" content:
     *   line 1: key URI written into the playlist (players fetch the key here)
     *   line 2: local path to the raw 16-byte key file FFmpeg reads to encrypt
     *   line 3: IV in hex
     *
     * @param keyDeliveryUri URI players will use to fetch the key (our token-gated endpoint)
     * @param localKeyFilePath absolute path to the binary key file on disk
     * @param ivHex 32-char hex IV
     */
    public static String buildKeyInfoFile(String keyDeliveryUri, String localKeyFilePath, String ivHex) {
        return keyDeliveryUri + "\n" + localKeyFilePath + "\n" + ivHex + "\n";
    }
}
