package tv.keeloke.plugins.conference;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * Cluster-shared conference room registry. Rooms live in Redis so participants
 * connected to different nodes share one consistent membership view, and
 * join/leave are guarded by a per-room distributed lock so concurrent joins
 * can't overshoot capacity.
 */
@Component
public class RoomManager {

    private static final Logger logger = LoggerFactory.getLogger(RoomManager.class);
    private static final String ROOMS_MAP = "keeloke:conference:rooms";

    @Value("${keeloke.conference.redisAddress:redis://127.0.0.1:6379}")
    private String redisAddress;

    @Value("${keeloke.conference.defaultMaxPublishers:16}")
    private int defaultMaxPublishers;

    @Value("${keeloke.conference.defaultMaxParticipants:200}")
    private int defaultMaxParticipants;

    private RedissonClient redisson;

    @PostConstruct
    public void init() {
        Config config = new Config();
        config.useSingleServer().setAddress(redisAddress);
        this.redisson = Redisson.create(config);
        logger.info("Keeloke RoomManager initialized. redis={}", redisAddress);
    }

    @PreDestroy
    public void shutdown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    private RMap<String, ConferenceRoom> rooms() {
        return redisson.getMap(ROOMS_MAP);
    }

    public ConferenceRoom create(String roomId, Integer maxPublishers, Integer maxParticipants) {
        ConferenceRoom room = new ConferenceRoom(roomId,
                maxPublishers != null ? maxPublishers : defaultMaxPublishers,
                maxParticipants != null ? maxParticipants : defaultMaxParticipants,
                System.currentTimeMillis());
        rooms().put(roomId, room);
        return room;
    }

    public ConferenceRoom get(String roomId) {
        return rooms().get(roomId);
    }

    public Collection<ConferenceRoom> list() {
        return rooms().readAllValues();
    }

    public void delete(String roomId) {
        rooms().remove(roomId);
    }

    /** Join under a per-room lock so capacity checks are race-free across nodes. */
    public ConferenceRoom.JoinOutcome join(String roomId, String participantId, ConferenceRoom.Role role) {
        RLock lock = redisson.getLock("keeloke:conference:lock:" + roomId);
        try {
            lock.lock(5, TimeUnit.SECONDS);
            ConferenceRoom room = rooms().get(roomId);
            if (room == null) {
                room = create(roomId, null, null);
            }
            ConferenceRoom.JoinOutcome outcome = room.join(participantId, role);
            if (outcome == ConferenceRoom.JoinOutcome.JOINED) {
                rooms().put(roomId, room);
            }
            return outcome;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    public boolean leave(String roomId, String participantId) {
        RLock lock = redisson.getLock("keeloke:conference:lock:" + roomId);
        try {
            lock.lock(5, TimeUnit.SECONDS);
            ConferenceRoom room = rooms().get(roomId);
            if (room == null) {
                return false;
            }
            boolean removed = room.leave(participantId);
            if (room.isEmpty()) {
                rooms().remove(roomId);
            } else {
                rooms().put(roomId, room);
            }
            return removed;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
