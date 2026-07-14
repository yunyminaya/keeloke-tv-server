package tv.keeloke.plugins.security;

import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Cluster-shared store for {@link TenantSecurityConfig}, backed by the same
 * Redis instance used by cluster-registry-plugin and tenant-quota-plugin so
 * every node of a multi-node deployment enforces identical security rules.
 */
@Component
public class SecurityConfigStore {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfigStore.class);
    private static final String CONFIG_MAP_NAME = "keeloke:security:tenant-config";

    @Value("${keeloke.security.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    private RedissonClient redisson;

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);
        logger.info("Keeloke SecurityConfigStore initialized. redis={}", redisAddress);
    }

    private RMap<String, TenantSecurityConfig> map() {
        return redisson.getMap(CONFIG_MAP_NAME);
    }

    public TenantSecurityConfig get(String tenantApp) {
        TenantSecurityConfig cfg = map().get(tenantApp);
        return cfg != null ? cfg : new TenantSecurityConfig(tenantApp);
    }

    public void save(TenantSecurityConfig config) {
        map().put(config.getTenantApp(), config);
    }

    public java.util.Map<String, TenantSecurityConfig> all() {
        return map().readAllMap();
    }
}
