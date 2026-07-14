package tv.keeloke.plugins.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The "any provider on the market" escape hatch. POSTs the frame (base64) to a
 * URL you control and reads flagged/score out of the JSON response using
 * configurable dot-notation paths - so you can front AWS Rekognition, Google
 * Cloud Vision, Azure Content Safety, Hive, Sightengine, or your own microservice
 * with a thin adapter and never touch this code.
 *
 * Request body: {"image_base64":"...","mime":"image/jpeg"}
 * API key is sent in the configured header (default Authorization: Bearer KEY).
 * Response is parsed via genericFlaggedPath (boolean or number) and
 * genericScorePath (number) - see {@link #valueAtPath}.
 */
public class GenericHttpProvider implements AiProvider {

    private static final Logger logger = LoggerFactory.getLogger(GenericHttpProvider.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final AiModerationConfig config;

    public GenericHttpProvider(AiModerationConfig config) {
        this.config = config;
    }

    @Override
    public ModerationResult analyzeImage(byte[] jpegBytes) {
        ModerationResult result = new ModerationResult();
        result.setProvider("generic");
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("image_base64", Base64.getEncoder().encodeToString(jpegBytes));
            body.put("mime", "image/jpeg");

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(config.getBaseUrl()))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));

            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                String headerName = config.getGenericApiKeyHeader() != null && !config.getGenericApiKeyHeader().isBlank()
                        ? config.getGenericApiKeyHeader() : "Authorization";
                String prefix = config.getGenericApiKeyPrefix() != null ? config.getGenericApiKeyPrefix() : "";
                builder.header(headerName, prefix + config.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                logger.warn("Generic provider HTTP {}: {}", response.statusCode(), response.body());
                return result;
            }
            JsonNode json = mapper.readTree(response.body());

            JsonNode flaggedNode = valueAtPath(json, config.getGenericFlaggedPath());
            JsonNode scoreNode = valueAtPath(json, config.getGenericScorePath());

            double score = scoreNode != null ? scoreNode.asDouble(0.0) : 0.0;
            result.addCategory("generic", score);

            boolean flaggedExplicit = flaggedNode != null && (flaggedNode.asBoolean(false) || flaggedNode.asDouble(0.0) >= 0.5);
            if (flaggedExplicit) {
                result.setFlagged(true);
                result.setReason("provider returned flagged=" + flaggedNode.asText());
            }
        } catch (Exception e) {
            logger.warn("Generic provider error: {}", e.getMessage());
        }
        return result;
    }

    /** Navigates dot-notation ("result.moderation.flagged") and array indices ("labels.0.name"). */
    static JsonNode valueAtPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String segment : path.split("\\.")) {
            if (current == null || current.isMissingNode()) {
                return null;
            }
            if (segment.matches("\\d+")) {
                current = current.path(Integer.parseInt(segment));
            } else {
                current = current.path(segment);
            }
        }
        return current.isMissingNode() ? null : current;
    }

    @Override
    public String name() {
        return "generic";
    }
}
