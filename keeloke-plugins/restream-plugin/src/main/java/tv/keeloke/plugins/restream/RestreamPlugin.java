package tv.keeloke.plugins.restream;

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
import java.util.List;

/**
 * Keeloke TV Server - Restream Plugin.
 *
 * Registers itself with the running Ant Media application as an
 * {@link IStreamListener}. When a stream starts, it looks up any configured
 * external destinations for that stream and forwards (restreams) it to all
 * of them simultaneously via FFmpeg. When the stream ends, all restreams for
 * it are stopped.
 *
 * This is a Community Edition-compatible plugin: it only uses the public
 * io.antmedia.plugin.api surface, no Enterprise classes.
 */
@Component
public class RestreamPlugin implements IStreamListener, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(RestreamPlugin.class);

    @Value("${server.rtmp_port:1935}")
    private int rtmpPort;

    @Value("${keeloke.restream.ffmpegPath:/usr/local/bin/ffmpeg}")
    private String ffmpegPath;

    @Value("${keeloke.restream.destinationsFile:conf/keeloke-restream-destinations.json}")
    private String destinationsFile;

    private ApplicationContext applicationContext;
    private RestreamDestinationStore store;
    private RestreamProcessManager processManager;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        this.store = new RestreamDestinationStore(destinationsFile);
        this.processManager = new RestreamProcessManager(ffmpegPath);

        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        adapter.addStreamListener(this);
        logger.info("Keeloke Restream Plugin initialized. destinationsFile={}", destinationsFile);
    }

    public RestreamDestinationStore getStore() {
        return store;
    }

    public RestreamProcessManager getProcessManager() {
        return processManager;
    }

    @Override
    public void streamStarted(Broadcast broadcast) {
        String streamId = broadcast.getStreamId();
        List<RestreamDestination> destinations = store.forStream(streamId);
        if (destinations.isEmpty()) {
            return;
        }

        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        String appName = adapter.getAppSettings().getAppName();

        String sourceUrl = "rtmp://127.0.0.1:" + rtmpPort + "/" + appName + "/" + streamId;
        logger.info("Stream {} started - forwarding to {} destination(s)", streamId, destinations.size());
        processManager.startAll(sourceUrl, streamId, destinations);
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        String streamId = broadcast.getStreamId();
        processManager.stopAllForStream(streamId);
        logger.info("Stream {} finished - all restreams stopped", streamId);
    }

    @Override
    public void joinedTheRoom(String roomId, String streamId) {
        // not applicable to restreaming - required by the interface
    }

    @Override
    public void leftTheRoom(String roomId, String streamId) {
        // not applicable to restreaming - required by the interface
    }
}
