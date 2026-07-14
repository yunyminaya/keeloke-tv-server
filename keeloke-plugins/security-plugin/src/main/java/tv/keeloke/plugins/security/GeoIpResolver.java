package tv.keeloke.plugins.security;

/**
 * Pluggable IP -> ISO-3166-1 alpha-2 country code resolver.
 *
 * No geo-IP database ships in this plugin: MaxMind GeoLite2 (the common free
 * option) requires its own free account and license key to redistribute, and
 * that's a decision/registration only you can make - not something this repo
 * can bundle. Point {@code keeloke.security.geoip.databasePath} at a
 * GeoLite2-Country.mmdb you've downloaded yourself and swap
 * {@link NoOpGeoIpResolver} for a MaxMind-backed implementation (a handful of
 * lines with com.maxmind.geoip2:geoip2 on the classpath) to enable real
 * country lookups; until then geo-restriction/geo-analytics degrade to
 * "unknown" rather than silently pretending to work.
 */
public interface GeoIpResolver {

    /** @return ISO-3166-1 alpha-2 country code, or "XX" if unresolvable. */
    String countryOf(String ip);
}
