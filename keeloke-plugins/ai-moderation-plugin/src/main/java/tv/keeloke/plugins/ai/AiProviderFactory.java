package tv.keeloke.plugins.ai;

/** Builds the configured {@link AiProvider}. Unknown/blank/"disabled" -> {@link DisabledProvider}. */
public final class AiProviderFactory {

    private AiProviderFactory() {
    }

    public static AiProvider from(AiModerationConfig config) {
        if (config == null || !config.isEnabled()) {
            return new DisabledProvider();
        }
        switch (config.getProvider().trim().toLowerCase()) {
            case "openai":
                return new OpenAiCompatibleProvider(config.getBaseUrl(), config.getApiKey(), config.getModel(), config.getPrompt());
            case "anthropic":
                return new AnthropicProvider(config.getBaseUrl(), config.getApiKey(), config.getModel(), config.getPrompt());
            case "generic":
                return new GenericHttpProvider(config);
            default:
                return new DisabledProvider();
        }
    }
}
