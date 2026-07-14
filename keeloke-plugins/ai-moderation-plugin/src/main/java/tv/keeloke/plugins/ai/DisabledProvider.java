package tv.keeloke.plugins.ai;

/** Default provider: analyzes nothing, flags nothing. Active until you configure a real provider + API key. */
public class DisabledProvider implements AiProvider {
    @Override
    public ModerationResult analyzeImage(byte[] jpegBytes) {
        return ModerationResult.clean("disabled");
    }

    @Override
    public String name() {
        return "disabled";
    }
}
