package tv.keeloke.plugins.drm;

import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

/**
 * Cluster-shared store of per-stream AES-128 keys (hex-encoded), so any node
 * serving the key-delivery endpoint can answer for a stream encrypted on
 * another node. Keys live only while the stream is active and are removed on
 * stream end.
 */
@Component
public class HlsKeyStore {

    private static final Logger logger = LoggerFactory.getLogger(HlsKeyStore.class);
    private static final String KEY_MAP = "keeloke:drm:hls-keys";

    @Value("${keeloke.drm.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    private RedissonClient redisson;

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);
        logger.info("Keeloke HlsKeyStore initialized. redis={}", redisAddress);
    }

    @PreDestroy
    public void shutdown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    private RMap<String, String> map() {
        return redisson.getMap(KEY_MAP);
    }

    public void put(String streamId, byte[] key) {
        map().put(streamId, HlsKeyUtil.toHex(key));
    }

    /** @return the raw 16-byte key for a stream, or null if not encrypted / expired. */
    public byte[] get(String streamId) {
        String hex = map().get(streamId);
        return hex != null ? HlsKeyUtil.fromHex(hex) : null;
    }

    public void remove(String streamId) {
        map().remove(streamId);
    }

    public boolean isEncrypted(String streamId) {
        return map().containsKey(streamId);
    }
}
