package tv.keeloke.plugins.security;

import org.springframework.stereotype.Component;

/** Default {@link GeoIpResolver}: always reports "XX" (unknown). See {@link GeoIpResolver} for how to replace it. */
@Component
public class NoOpGeoIpResolver implements GeoIpResolver {
    @Override
    public String countryOf(String ip) {
        return "XX";
    }
}
