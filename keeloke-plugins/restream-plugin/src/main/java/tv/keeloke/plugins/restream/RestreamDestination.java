package tv.keeloke.plugins.restream;

/**
 * One external RTMP target a stream should be forwarded to
 * (e.g. YouTube Live, Facebook Live, Twitch, or any custom RTMP endpoint).
 */
public class RestreamDestination {

    private String id;
    private String streamId;
    private String name;
    private String rtmpUrl;
    private boolean enabled = true;

    public RestreamDestination() {
    }

    public RestreamDestination(String id, String streamId, String name, String rtmpUrl) {
        this.id = id;
        this.streamId = streamId;
        this.name = name;
        this.rtmpUrl = rtmpUrl;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStreamId() {
        return streamId;
    }

    public void setStreamId(String streamId) {
        this.streamId = streamId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRtmpUrl() {
        return rtmpUrl;
    }

    public void setRtmpUrl(String rtmpUrl) {
        this.rtmpUrl = rtmpUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
