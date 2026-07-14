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
import java.util.Iterator;
import java.util.Map;

/**
 * Works with OpenAI and ANY OpenAI-compatible chat/vision API: Groq, Together,
 * OpenRouter, Mistral, DeepSeek, a self-hosted vLLM/llama.cpp server - anything
 * that speaks POST {baseUrl}/chat/completions with the standard messages +
 * image_url content shape. Just set baseUrl + apiKey + model.
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatibleProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String prompt;

    public OpenAiCompatibleProvider(String baseUrl, String apiKey, String model, String prompt) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.openai.com/v1" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? "gpt-4o-mini" : model;
        this.prompt = prompt;
    }

    @Override
    public ModerationResult analyzeImage(byte[] jpegBytes) {
        try {
            String dataUrl = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpegBytes);

            ObjectNode root = mapper.createObjectNode();
            root.put("model", model);
            root.put("temperature", 0);
            ArrayNode messages = root.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            ArrayNode content = userMsg.putArray("content");
            content.addObject().put("type", "text").put("text", prompt);
            ObjectNode imgPart = content.addObject();
            imgPart.put("type", "image_url");
            imgPart.putObject("image_url").put("url", dataUrl);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(root), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warn("OpenAI-compatible provider HTTP {}: {}", response.statusCode(), truncate(response.body()));
                return ModerationResult.clean("openai");
            }
            JsonNode json = mapper.readTree(response.body());
            String text = json.path("choices").path(0).path("message").path("content").asText("");
            return parseModelJson(text, "openai");
        } catch (Exception e) {
            logger.warn("OpenAI-compatible provider error: {}", e.getMessage());
            return ModerationResult.clean("openai");
        }
    }

    /** Extracts the {categories:{}, labels:[]} JSON the vision model was asked to return (tolerating code fences / prose). */
    ModerationResult parseModelJson(String text, String providerName) {
        ModerationResult result = new ModerationResult();
        result.setProvider(providerName);
        try {
            String jsonSlice = extractJsonObject(text);
            if (jsonSlice == null) {
                return result;
            }
            JsonNode node = mapper.readTree(jsonSlice);
            JsonNode categories = node.get("categories");
            if (categories != null && categories.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = categories.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    result.addCategory(entry.getKey(), entry.getValue().asDouble(0.0));
                }
            }
            JsonNode labels = node.get("labels");
            if (labels != null && labels.isArray()) {
                labels.forEach(l -> result.getDetectedLabels().add(l.asText()));
            }
        } catch (Exception e) {
            logger.debug("Could not parse model JSON: {}", e.getMessage());
        }
        return result;
    }

    static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return null;
    }

    private static String truncate(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) : s;
    }

    @Override
    public String name() {
        return "openai";
    }
}
