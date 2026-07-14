package tv.keeloke.plugins.cluster;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.UUID;

/**
 * Single source of truth for this JVM's cluster identity and its shared
 * Redisson client, so the registry, load balancer, failover monitor and
 * autoscale advisor all speak to the same Redis and agree on this node's id
 * (rather than each spinning up its own client and a different random id).
 */
@Component
public class ClusterRedis {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRedis.class);

    @Value("${keeloke.cluster.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    /** Stable id for this node for the lifetime of the JVM. */
    private final String nodeId = UUID.randomUUID().toString();

    private RedissonClient redisson;

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);
        logger.info("Keeloke ClusterRedis initialized. nodeId={} redis={}", nodeId, redisAddress);
    }

    @PreDestroy
    public void shutdown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    public RedissonClient client() {
        return redisson;
    }

    public String nodeId() {
        return nodeId;
    }

    public String redisAddress() {
        return redisAddress;
    }
}
