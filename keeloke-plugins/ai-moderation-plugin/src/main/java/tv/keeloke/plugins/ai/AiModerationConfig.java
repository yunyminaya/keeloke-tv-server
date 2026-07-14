package tv.keeloke.plugins.ai;

import java.io.Serializable;

/**
 * Per-deployment AI moderation/detection configuration. Stored in Redis (so a
 * cluster shares it) and editable at runtime via REST. Defaults are
 * fully disabled, so installing the plugin changes nothing until you add a key.
 */
public class AiModerationConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /** openai | anthropic | generic | disabled */
    private String provider = "disabled";

    /** Your API key for the chosen provider. Never logged. */
    private String apiKey = "";

    /**
     * Base URL / endpoint.
     *  - openai: defaults to https://api.openai.com/v1 (override for Groq/Together/OpenRouter/local)
     *  - anthropic: defaults to https://api.anthropic.com/v1
     *  - generic: the full URL the frame is POSTed to
     */
    private String baseUrl = "";

    /** Model id for chat/vision providers (e.g. gpt-4o-mini, claude-sonnet-5). */
    private String model = "";

    /** Instruction sent to vision LLMs describing what to moderate/detect. */
    private String prompt =
            "You are a content-moderation classifier. Look at this video frame and respond ONLY with compact JSON: "
            + "{\"categories\":{\"sexual\":0-1,\"violence\":0-1,\"weapons\":0-1,\"drugs\":0-1,\"hate\":0-1},"
            + "\"labels\":[\"object1\",\"object2\"]}. Scores are your confidence 0..1.";

    /** Category score at/above which a frame is flagged. */
    private double flagThreshold = 0.7;

    /** Seconds between sampled frames per stream. */
    private int sampleIntervalSeconds = 15;

    /** log | webhook | stop  (comma-separated to combine, e.g. "webhook,stop"). */
    private String actionOnFlag = "log,webhook";

    /** Webhook notified when a frame is flagged (if actionOnFlag contains "webhook"). */
    private String webhookUrl = "";

    /** For provider=generic: JSON field path to the boolean/score, dot-notation (e.g. "result.flagged"). */
    private String genericFlaggedPath = "flagged";
    private String genericScorePath = "score";

    /** For provider=generic: header name to carry the API key (e.g. "Authorization" or "x-api-key"). */
    private String genericApiKeyHeader = "Authorization";
    private String genericApiKeyPrefix = "Bearer ";

    public boolean isEnabled() {
        return provider != null && !provider.equalsIgnoreCase("disabled") && !provider.isBlank();
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public double getFlagThreshold() { return flagThreshold; }
    public void setFlagThreshold(double flagThreshold) { this.flagThreshold = flagThreshold; }

    public int getSampleIntervalSeconds() { return sampleIntervalSeconds; }
    public void setSampleIntervalSeconds(int sampleIntervalSeconds) { this.sampleIntervalSeconds = sampleIntervalSeconds; }

    public String getActionOnFlag() { return actionOnFlag; }
    public void setActionOnFlag(String actionOnFlag) { this.actionOnFlag = actionOnFlag; }

    public String getWebhookUrl() { return webhookUrl; }
    public void setWebhookUrl(String webhookUrl) { this.webhookUrl = webhookUrl; }

    public String getGenericFlaggedPath() { return genericFlaggedPath; }
    public void setGenericFlaggedPath(String genericFlaggedPath) { this.genericFlaggedPath = genericFlaggedPath; }

    public String getGenericScorePath() { return genericScorePath; }
    public void setGenericScorePath(String genericScorePath) { this.genericScorePath = genericScorePath; }

    public String getGenericApiKeyHeader() { return genericApiKeyHeader; }
    public void setGenericApiKeyHeader(String genericApiKeyHeader) { this.genericApiKeyHeader = genericApiKeyHeader; }

    public String getGenericApiKeyPrefix() { return genericApiKeyPrefix; }
    public void setGenericApiKeyPrefix(String genericApiKeyPrefix) { this.genericApiKeyPrefix = genericApiKeyPrefix; }
}
