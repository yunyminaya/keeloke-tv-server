package tv.keeloke.plugins.ai;

/**
 * A pluggable AI vision backend. Implementations wrap whatever provider you
 * have an API key for - the design goal is that you can point Keeloke TV
 * Server at ANY vision/moderation API on the market by picking a provider name
 * and dropping in your key, without code changes.
 *
 * Shipped implementations:
 *   - {@link OpenAiCompatibleProvider}: OpenAI and any OpenAI-compatible endpoint
 *     (Groq, Together, OpenRouter, Mistral, a local llama.cpp/vLLM server, ...)
 *   - {@link AnthropicProvider}: Claude vision (Anthropic Messages API)
 *   - {@link GenericHttpProvider}: POST the frame to ANY HTTP endpoint and read
 *     the flagged/score out of the JSON via configurable field paths - the
 *     escape hatch for AWS Rekognition, Google Cloud Vision, Azure Content
 *     Safety, Hive, Sightengine, or your own service, behind a tiny adapter URL
 *   - {@link DisabledProvider}: default no-op
 */
public interface AiProvider {

    /** @return a normalized result; implementations must never throw - return a clean result on error and log. */
    ModerationResult analyzeImage(byte[] jpegBytes);

    String name();
}
