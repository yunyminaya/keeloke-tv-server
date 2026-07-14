package tv.keeloke.plugins.ai;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic result of analyzing one video frame: whether it should be
 * flagged, why, per-category confidence scores (moderation), and detected
 * object/labels (detection). Every {@link AiProvider} maps its own vendor
 * response into this shape so the rest of Keeloke TV Server never depends on a
 * specific AI vendor's JSON.
 */
public class ModerationResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private boolean flagged;
    private double maxScore;
    private String reason = "";
    private Map<String, Double> categoryScores = new LinkedHashMap<>();
    private List<String> detectedLabels = new ArrayList<>();
    private String provider = "";
    private long analyzedAtEpochMs = System.currentTimeMillis();

    public ModerationResult() {
    }

    public static ModerationResult clean(String provider) {
        ModerationResult r = new ModerationResult();
        r.provider = provider;
        r.flagged = false;
        return r;
    }

    /** Applies a flag threshold to the category scores, setting flagged/reason accordingly. */
    public ModerationResult evaluate(double flagThreshold) {
        String worstCategory = null;
        double worst = 0.0;
        for (Map.Entry<String, Double> entry : categoryScores.entrySet()) {
            if (entry.getValue() > worst) {
                worst = entry.getValue();
                worstCategory = entry.getKey();
            }
        }
        this.maxScore = worst;
        if (worst >= flagThreshold) {
            this.flagged = true;
            this.reason = "category '" + worstCategory + "' scored " + String.format(java.util.Locale.ROOT, "%.3f", worst)
                    + " >= threshold " + flagThreshold;
        }
        return this;
    }

    public void addCategory(String name, double score) {
        categoryScores.put(name, score);
    }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }

    public double getMaxScore() { return maxScore; }
    public void setMaxScore(double maxScore) { this.maxScore = maxScore; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Map<String, Double> getCategoryScores() { return categoryScores; }
    public void setCategoryScores(Map<String, Double> categoryScores) { this.categoryScores = categoryScores; }

    public List<String> getDetectedLabels() { return detectedLabels; }
    public void setDetectedLabels(List<String> detectedLabels) { this.detectedLabels = detectedLabels; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public long getAnalyzedAtEpochMs() { return analyzedAtEpochMs; }
    public void setAnalyzedAtEpochMs(long analyzedAtEpochMs) { this.analyzedAtEpochMs = analyzedAtEpochMs; }
}
