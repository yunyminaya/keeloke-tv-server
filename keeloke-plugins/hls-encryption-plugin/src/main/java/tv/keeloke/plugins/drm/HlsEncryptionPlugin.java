package tv.keeloke.plugins.drm;

import io.antmedia.AntMediaApplicationAdapter;
import io.antmedia.datastore.db.types.Broadcast;
import io.antmedia.plugin.api.IStreamListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * Wires HLS AES-128 encryption to the stream lifecycle: on stream start it
 * begins producing token-gated encrypted HLS; on stream end it stops and
 * revokes the key. Disabled unless keeloke.drm.enabled=true.
 */
@Component
public class HlsEncryptionPlugin implements IStreamListener, ApplicationContextAware {

    private static final Logger logger = LoggerFactory.getLogger(HlsEncryptionPlugin.class);

    @Value("${server.rtmp_port:1935}")
    private int rtmpPort;

    @Autowired
    private HlsEncryptionConfig config;

    @Autowired
    private HlsEncryptionManager manager;

    private ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        AntMediaApplicationAdapter adapter = applicationContext.getBean(AntMediaApplicationAdapter.class);
        adapter.addStreamListener(this);
        logger.info("Keeloke HlsEncryptionPlugin initialized (enabled={})", config.isEnabled());
    }

    @Override
    public void streamStarted(Broadcast broadcast) {
        if (!config.isEnabled() || broadcast == null || broadcast.getStreamId() == null) {
            return;
        }
        manager.start(appName(), broadcast.getStreamId(), rtmpPort);
    }

    @Override
    public void streamFinished(Broadcast broadcast) {
        if (broadcast != null && broadcast.getStreamId() != null) {
            manager.stop(broadcast.getStreamId());
        }
    }

    private String appName() {
        try {
            return applicationContext.getBean(AntMediaApplicationAdapter.class).getAppSettings().getAppName();
        } catch (Exception e) {
            return config.getApp();
        }
    }

    @Override
    public void joinedTheRoom(String roomId, String streamId) {
    }

    @Override
    public void leftTheRoom(String roomId, String streamId) {
    }
}
