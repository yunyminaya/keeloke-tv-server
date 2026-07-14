package tv.keeloke.plugins.security;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/** Aggregated, per-stream analytics snapshot returned by the REST API. */
public class StreamAnalytics implements Serializable {

    private static final long serialVersionUID = 1L;

    private String streamId;
    private long totalPlaySessions;
    private long currentConcurrentViewers;
    private Map<String, Long> viewersByCountry = new HashMap<>();

    public StreamAnalytics() {
    }

    public StreamAnalytics(String streamId) {
        this.streamId = streamId;
    }

    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }

    public long getTotalPlaySessions() { return totalPlaySessions; }
    public void setTotalPlaySessions(long totalPlaySessions) { this.totalPlaySessions = totalPlaySessions; }

    public long getCurrentConcurrentViewers() { return currentConcurrentViewers; }
    public void setCurrentConcurrentViewers(long currentConcurrentViewers) { this.currentConcurrentViewers = currentConcurrentViewers; }

    public Map<String, Long> getViewersByCountry() { return viewersByCountry; }
    public void setViewersByCountry(Map<String, Long> viewersByCountry) { this.viewersByCountry = viewersByCountry; }
}
