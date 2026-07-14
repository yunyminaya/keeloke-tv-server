package tv.keeloke.plugins.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Claude vision via the Anthropic Messages API. Set apiKey + model
 * (e.g. claude-sonnet-5). Reuses OpenAiCompatibleProvider's tolerant JSON
 * extraction for the model's reply.
 */
public class AnthropicProvider implements AiProvider {

    private static final Logger logger = LoggerFactory.getLogger(AnthropicProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final OpenAiCompatibleProvider jsonParser = new OpenAiCompatibleProvider("", "", "", "");

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String prompt;

    public AnthropicProvider(String baseUrl, String apiKey, String model, String prompt) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.anthropic.com/v1" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? "claude-sonnet-5" : model;
        this.prompt = prompt;
    }

    @Override
    public ModerationResult analyzeImage(byte[] jpegBytes) {
        try {
            String b64 = Base64.getEncoder().encodeToString(jpegBytes);

            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("max_tokens", 512);
            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image");
            ObjectNode source = imgPart.putObject("source");
            source.put("type", "base64");
            source.put("media_type", "image/jpeg");
            source.put("data", b64);
            content.addObject().put("type", "text").put("text", prompt);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/messages"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warn("Anthropic provider HTTP {}: {}", response.statusCode(), response.body());
                return ModerationResult.clean("anthropic");
            }
            JsonNode json = mapper.readTree(response.body());
            String text = json.path("content").path(0).path("text").asText("");
            return jsonParser.parseModelJson(text, "anthropic");
        } catch (Exception e) {
            logger.warn("Anthropic provider error: {}", e.getMessage());
            return ModerationResult.clean("anthropic");
        }
    }

    @Override
    public String name() {
        return "anthropic";
    }
}
