package tv.keeloke.plugins.cluster;

public class ClusterNodeInfo {

    private String nodeId;
    private String host;
    private int rtmpPort;
    private int httpPort;
    private int activeStreamCount;
    private long lastHeartbeatEpochMs;

    public ClusterNodeInfo() {
    }

    public ClusterNodeInfo(String nodeId, String host, int rtmpPort, int httpPort, int activeStreamCount, long lastHeartbeatEpochMs) {
        this.nodeId = nodeId;
        this.host = host;
        this.rtmpPort = rtmpPort;
        this.httpPort = httpPort;
        this.activeStreamCount = activeStreamCount;
        this.lastHeartbeatEpochMs = lastHeartbeatEpochMs;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getRtmpPort() {
        return rtmpPort;
    }

    public void setRtmpPort(int rtmpPort) {
        this.rtmpPort = rtmpPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public void setHttpPort(int httpPort) {
        this.httpPort = httpPort;
    }

    public int getActiveStreamCount() {
        return activeStreamCount;
    }

    public void setActiveStreamCount(int activeStreamCount) {
        this.activeStreamCount = activeStreamCount;
    }

    public long getLastHeartbeatEpochMs() {
        return lastHeartbeatEpochMs;
    }

    public void setLastHeartbeatEpochMs(long lastHeartbeatEpochMs) {
        this.lastHeartbeatEpochMs = lastHeartbeatEpochMs;
    }
}
