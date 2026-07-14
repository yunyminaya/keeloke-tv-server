package tv.keeloke.plugins.cluster;

import java.io.Serializable;

public class ClusterNodeInfo implements Serializable {

    private static final long serialVersionUID = 2L;

    public enum Role { ORIGIN, EDGE, HYBRID }

    private String nodeId;
    private String host;
    private int rtmpPort;
    private int httpPort;
    private int activeStreamCount;
    private long lastHeartbeatEpochMs;

    // --- v2 fields for load balancing / autoscaling / failover ---
    private Role role = Role.HYBRID;
    private String region = "default";
    private int maxStreamCapacity = 100;
    private double cpuLoadPercent = 0.0;
    private long viewerCount = 0;

    public ClusterNodeInfo() {
    }

    /** Backward-compatible constructor (used by the original registry heartbeat). */
    public ClusterNodeInfo(String nodeId, String host, int rtmpPort, int httpPort, int activeStreamCount, long lastHeartbeatEpochMs) {
        this.nodeId = nodeId;
        this.host = host;
        this.rtmpPort = rtmpPort;
        this.httpPort = httpPort;
        this.activeStreamCount = activeStreamCount;
        this.lastHeartbeatEpochMs = lastHeartbeatEpochMs;
    }

    /**
     * Normalized load score in [0,1]: how "full" this node is. Combines stream
     * saturation (streams/capacity) and CPU load, weighted toward whichever is
     * worse, so a node that's CPU-bound but stream-light is still seen as busy.
     */
    public double loadScore() {
        double streamSaturation = maxStreamCapacity > 0
                ? Math.min(1.0, (double) activeStreamCount / maxStreamCapacity)
                : 1.0;
        double cpuSaturation = Math.min(1.0, cpuLoadPercent / 100.0);
        return Math.max(streamSaturation, cpuSaturation);
    }

    public boolean canAcceptPublish() {
        return (role == Role.ORIGIN || role == Role.HYBRID) && activeStreamCount < maxStreamCapacity;
    }

    public String httpBaseUrl() {
        return "http://" + host + ":" + httpPort;
    }

    public String rtmpBaseUrl() {
        return "rtmp://" + host + ":" + rtmpPort;
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getRtmpPort() { return rtmpPort; }
    public void setRtmpPort(int rtmpPort) { this.rtmpPort = rtmpPort; }

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public int getActiveStreamCount() { return activeStreamCount; }
    public void setActiveStreamCount(int activeStreamCount) { this.activeStreamCount = activeStreamCount; }

    public long getLastHeartbeatEpochMs() { return lastHeartbeatEpochMs; }
    public void setLastHeartbeatEpochMs(long lastHeartbeatEpochMs) { this.lastHeartbeatEpochMs = lastHeartbeatEpochMs; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public int getMaxStreamCapacity() { return maxStreamCapacity; }
    public void setMaxStreamCapacity(int maxStreamCapacity) { this.maxStreamCapacity = maxStreamCapacity; }

    public double getCpuLoadPercent() { return cpuLoadPercent; }
    public void setCpuLoadPercent(double cpuLoadPercent) { this.cpuLoadPercent = cpuLoadPercent; }

    public long getViewerCount() { return viewerCount; }
    public void setViewerCount(long viewerCount) { this.viewerCount = viewerCount; }
}
