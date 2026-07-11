package tv.keeloke.plugins.tenant;

/**
 * Aggregate usage snapshot for one tenant (Ant Media "application" == tenant
 * in this simple model - each app/tenant already gets its own name, users,
 * and streams natively).
 */
public class TenantUsage {

    private String tenantApp;
    private int activeStreams;
    private int maxConcurrentStreams;
    private long totalStreamMinutes;
    private long totalStreamsStarted;

    public TenantUsage() {
    }

    public TenantUsage(String tenantApp, int activeStreams, int maxConcurrentStreams,
                        long totalStreamMinutes, long totalStreamsStarted) {
        this.tenantApp = tenantApp;
        this.activeStreams = activeStreams;
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.totalStreamMinutes = totalStreamMinutes;
        this.totalStreamsStarted = totalStreamsStarted;
    }

    public String getTenantApp() {
        return tenantApp;
    }

    public void setTenantApp(String tenantApp) {
        this.tenantApp = tenantApp;
    }

    public int getActiveStreams() {
        return activeStreams;
    }

    public void setActiveStreams(int activeStreams) {
        this.activeStreams = activeStreams;
    }

    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public void setMaxConcurrentStreams(int maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public long getTotalStreamMinutes() {
        return totalStreamMinutes;
    }

    public void setTotalStreamMinutes(long totalStreamMinutes) {
        this.totalStreamMinutes = totalStreamMinutes;
    }

    public long getTotalStreamsStarted() {
        return totalStreamsStarted;
    }

    public void setTotalStreamsStarted(long totalStreamsStarted) {
        this.totalStreamsStarted = totalStreamsStarted;
    }
}
