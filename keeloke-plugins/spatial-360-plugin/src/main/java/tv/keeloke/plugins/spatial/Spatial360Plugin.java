package tv.keeloke.plugins.spatial;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.plugin.api.IStreamListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Marks recordings from selected streams as 360° by injecting Spherical Video
 * V1 metadata into their MP4 when the stream ends. A stream is treated as 360
 * if its id/name is in the configured set (keeloke.spatial.streams) OR the
 * whole app is 360 (keeloke.spatial.allStreams=true).
 *
 * Server-side 360 is a passthrough: the equirectangular pixels flow through the
 * normal pipeline unchanged; this metadata is what tells players to project
 * them on a sphere. So there's no transcode cost - just a post-record metadata
 * splice.
 */
@Component
public class Spatial360Plugin implements IStreamListener, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(Spatial360Plugin.class);

    @Value("${keeloke.spatial.enabled:false}")
    private boolean enabled;

    @Value("${keeloke.spatial.allStreams:false}")
    private boolean allStreams;

    @Value("${keeloke.spatial.stereoTopBottom:false}")
    private boolean stereoTopBottom;

    @Value("${keeloke.spatial.recordingsDir:/usr/local/antmedia/webapps/LiveApp/streams}")
    private String recordingsDir;

    private final Set<String> streams360 = ConcurrentHashMap.newKeySet();
    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        adapter.addStreamListener(this);
        logger.info("Keeloke Spatial360Plugin initialized (enabled={}, allStreams={})", enabled, allStreams);
    }

    public void mark360(String streamId) {
        streams360.add(streamId);
    }

    public void unmark360(String streamId) {
        streams360.remove(streamId);
    }

    public boolean is360(String streamId) {
        return allStreams || streams360.contains(streamId);
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        if (!enabled || broadcast == null || broadcast.getStreamId() == null) {
            return;
        }
        String streamId = broadcast.getStreamId();
        if (!is360(streamId)) {
            return;
        }
        // recordings are typically written a moment after the stream ends; do it off the event thread
        Thread t = new Thread(() -> injectWithRetry(streamId), "keeloke-360-" + streamId);
        t.setDaemon(true);
        t.start();
    }

    private void injectWithRetry(String streamId) {
        SphericalMetadata.Projection projection = SphericalMetadata.Projection.EQUIRECTANGULAR;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                Thread.sleep(2000L * attempt);
                Path mp4 = Paths.get(recordingsDir, streamId + ".mp4");
                if (!Files.exists(mp4)) {
                    continue;
                }
                Mp4SphericalInjector.Status status = Mp4SphericalInjector.injectFile(mp4, stereoTopBottom, projection);
                logger.info("360 metadata for {}.mp4: {}", streamId, status);
                if (status == Mp4SphericalInjector.Status.INJECTED
                        || status == Mp4SphericalInjector.Status.ALREADY_PRESENT) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warn("360 injection attempt {} for {} failed: {}", attempt, streamId, e.getMessage());
            }
        }
        logger.warn("Gave up injecting 360 metadata for {} (recording not found or unsupported layout)", streamId);
    }

    @Override
    public void streamStarted(Broadcast broadcast) {
    }

    @Override
    public void joinedTheRoom(String roomId, String streamId) {
    }

    @Override
    public void leftTheRoom(String roomId, String streamId) {
    }
}
