package tv.keeloke.plugins.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Minimal IPv4/IPv6 CIDR matcher - no external dependency. Accepts either a
 * bare IP ("203.0.113.5") or a CIDR block ("203.0.113.0/24").
 */
public final class CidrMatcher {

    private CidrMatcher() {
    }

    public static boolean matchesAny(String ip, List<String> cidrList) {
        if (ip == null || cidrList == null || cidrList.isEmpty()) {
            return false;
        }
        for (String cidr : cidrList) {
            if (matches(ip, cidr)) {
                return true;
            }
        }
        return false;
    }

    public static boolean matches(String ip, String cidr) {
        try {
            String[] parts = cidr.split("/", 2);
            InetAddress target = InetAddress.getByName(ip);
            InetAddress network = InetAddress.getByName(parts[0]);
            if (target.getAddress().length != network.getAddress().length) {
                return false;
            }
            int prefixLength = parts.length == 2
                    ? Integer.parseInt(parts[1])
                    : target.getAddress().length * 8;

            byte[] targetBytes = target.getAddress();
            byte[] networkBytes = network.getAddress();
            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (targetBytes[i] != networkBytes[i]) {
                    return false;
                }
            }
            if (remainingBits > 0) {
                int mask = 0xFF << (8 - remainingBits);
                if ((targetBytes[fullBytes] & mask) != (networkBytes[fullBytes] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }
}
