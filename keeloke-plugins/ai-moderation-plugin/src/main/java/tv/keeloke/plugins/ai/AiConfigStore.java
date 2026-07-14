package tv.keeloke.plugins.ai;

import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/** Cluster-shared store for the single active {@link AiModerationConfig}. */
@Component
public class AiConfigStore {

    private static final Logger logger = LoggerFactory.getLogger(AiConfigStore.class);
    private static final String CONFIG_KEY = "keeloke:ai:moderation-config";

    @Value("${keeloke.ai.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    private RedissonClient redisson;

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);
        logger.info("Keeloke AiConfigStore initialized. redis={}", redisAddress);
    }

    @PreDestroy
    public void shutdown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    public AiModerationConfig get() {
        RBucket<AiModerationConfig> bucket = redisson.getBucket(CONFIG_KEY);
        AiModerationConfig cfg = bucket.get();
        return cfg != null ? cfg : new AiModerationConfig();
    }

    public void save(AiModerationConfig config) {
        RBucket<AiModerationConfig> bucket = redisson.getBucket(CONFIG_KEY);
        bucket.set(config);
    }
}
