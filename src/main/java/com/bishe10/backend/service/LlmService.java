package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

@Service
public class LlmService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmService.class);

    private final Bishe10Properties.Llm properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmService(Bishe10Properties bishe10Properties, ObjectMapper objectMapper) {
        this.properties = bishe10Properties.getLlm();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    public Optional<Map<String, Object>> analyzeVision(String scene, byte[] imageBytes, String mimeType) {
        if (!properties.isEnabled() || imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }

        if (isTextReadingScene(scene)) {
            return requestStructuredJson(
                    "你服务的对象是视障人士，当前任务是从图片中提取全文并整理成可朗读文本。回答必须忠实于图片内容，不能编造、不能省略大段正文。",
                    buildTextReadingPrompt(),
                    imageBytes,
                    mimeType,
                    false,
                    null,
                    0.1,
                    2200
            );
        }

        return requestStructuredJson(
                "你服务的对象是视障人士，回答必须以安全和行动建议优先。",
                buildVisionPrompt(scene),
                imageBytes,
                mimeType,
                false,
                null,
                0.2,
                420
        );
    }

    public Optional<Map<String, Object>> analyzeVisionStreaming(
            String scene,
            byte[] imageBytes,
            String mimeType,
            Consumer<String> onChunk
    ) {
        if (!properties.isEnabled() || imageBytes == null || imageBytes.length == 0) {
            return Optional.empty();
        }

        if (isTextReadingScene(scene)) {
            return requestStructuredJson(
                    "你服务的对象是视障人士，当前任务是从图片中提取全文并整理成可朗读文本。回答必须忠实于图片内容，不能编造、不能省略大段正文。",
                    buildTextReadingPrompt(),
                    imageBytes,
                    mimeType,
                    true,
                    onChunk,
                    0.1,
                    2200
            );
        }

        return requestStructuredJson(
                "你服务的对象是视障人士，回答必须以安全和行动建议优先。",
                buildVisionPrompt(scene),
                imageBytes,
                mimeType,
                true,
                onChunk,
                0.2,
                420
        );
    }

    public String getProviderLabel() {
        return properties.getProviderLabel();
    }

    public boolean isConfigured() {
        return properties.isEnabled() && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank();
    }

    public String getModel() {
        return properties.getModel();
    }

    public String getVisionProviderLabel() {
        return properties.resolvedVisionProviderLabel();
    }

    public String getVisionModel() {
        return properties.resolvedVisionModel();
    }

    public boolean isVisionConfigured() {
        String base = properties.resolvedVisionBaseUrl();
        return properties.isEnabled() && base != null && !base.isBlank();
    }

    public Optional<Map<String, Object>> generateNewsDigest(String city, int count, List<String> existingTitles) {
        if (!isConfigured() || count <= 0) {
            return Optional.empty();
        }

        return requestTextStructuredJson(
                """
                        你是城市无障碍资讯助手，服务对象是视障人士。
                        输出必须是一个 JSON 对象，不能输出解释、代码块或任何额外文字。
                        如果内容不是已核实的实时新闻，必须写成“建议关注”“可优先确认”“出门前可先检查”这类辅助提醒，
                        不能伪装成政府公告、突发新闻或已确认事实。
                        """,
                buildNewsPrompt(city, count, existingTitles)
        );
    }

    public Optional<Map<String, Object>> interpretNews(String title, String summary, String content, String source, String category) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        String systemPrompt = """
                你是视障辅助资讯助手，为视障用户解读本地资讯。
                输出一个 JSON 对象，不能输出解释、代码块或任何额外文字。
                JSON 结构固定如下：
                {
                  "brief": "30到80字，概述资讯重点与对视障人士的实际影响",
                  "keyPoints": ["3到4条要点", "每条不超过24字", "强调可执行的建议"],
                  "spokenText": "40到90字的朗读短文，可直接作为语音播报"
                }
                """;
        String userPrompt = """
                资讯分类：%s
                标题：%s
                来源：%s
                摘要：%s
                正文片段：%s
                请结合上述信息输出 JSON。
                """.formatted(
                nullSafe(category),
                nullSafe(title),
                nullSafe(source),
                nullSafe(summary),
                nullSafe(content)
        );
        return requestTextStructuredJson(systemPrompt, userPrompt);
    }

    public Optional<Map<String, Object>> generateWeatherTravelAdvice(Map<String, Object> weatherPayload) {
        if (!isConfigured() || weatherPayload == null || weatherPayload.isEmpty()) {
            return Optional.empty();
        }

        String systemPrompt = """
                你是视障人士出行天气助手。
                只输出一个 JSON 对象，不能输出解释、代码块或任何额外文字。
                JSON 结构固定如下：
                {
                  "summary": "16到32字，先概括天气和风险级别",
                  "travelAdvice": "40到90字，给出适合视障人士的简洁出行建议",
                  "spokenText": "50到120字，可直接用于语音播报，先说天气再说建议"
                }
                要求：
                1. 优先突出降雨、风力、能见度、气温体感和预警。
                2. 建议要具体，可执行，不要空泛安慰。
                3. 如果风险较高，要明确提醒减少外出或优先结伴。
                """;
        String userPrompt = """
                城市：%s
                省份：%s
                天气：%s
                温度：%s℃
                体感：%s℃
                湿度：%s%%
                风速：%s km/h
                风向：%s
                降水：%s mm
                能见度：%s km
                气压：%s hPa
                空气质量：%s
                预警：%s
                更新时间：%s
                请输出 JSON。
                """.formatted(
                valueOf(weatherPayload.get("location")),
                valueOf(weatherPayload.get("province")),
                valueOf(weatherPayload.get("weather_text")),
                valueOf(weatherPayload.get("temperature")),
                valueOf(weatherPayload.get("feels_like")),
                valueOf(weatherPayload.get("humidity")),
                valueOf(weatherPayload.get("wind_speed")),
                valueOf(weatherPayload.get("wind_direction")),
                valueOf(weatherPayload.get("precipitation")),
                valueOf(weatherPayload.get("visibility")),
                valueOf(weatherPayload.get("pressure")),
                valueOf(weatherPayload.get("air_quality")),
                valueOf(weatherPayload.get("alert")),
                valueOf(weatherPayload.get("update_time"))
        );
        return requestTextStructuredJson(systemPrompt, userPrompt);
    }

    private String nullSafe(String text) {
        return text == null ? "" : text.trim();
    }

    private String valueOf(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private Optional<Map<String, Object>> requestStructuredJson(
            String systemPrompt,
            String userPrompt,
            byte[] imageBytes,
            String mimeType,
            boolean stream,
            Consumer<String> onChunk,
            double temperature,
            int maxTokens
    ) {
        try {
            String baseUrl = properties.resolvedVisionBaseUrl();
            String apiKey = properties.resolvedVisionApiKey();
            String model = properties.resolvedVisionModel();
            int timeoutSec = properties.resolvedVisionTimeoutSeconds();
            int effectiveMaxTokens = clampVisionMaxTokens(model, maxTokens);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", temperature);
            body.put("max_tokens", effectiveMaxTokens);
            body.put("messages", buildMessages(systemPrompt, userPrompt, imageBytes, mimeType));
            if (stream) {
                body.put("stream", true);
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(timeoutSec))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("authorization", "Bearer " + apiKey);
            }

            if (stream) {
                HttpResponse<java.io.InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    LOGGER.warn("LLM streaming request failed with status {}", response.statusCode());
                    return Optional.empty();
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    StringBuilder contentBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isBlank() || !line.startsWith("data:")) {
                            continue;
                        }

                        String data = line.substring(5).trim();
                        if (data.isBlank() || "[DONE]".equals(data)) {
                            continue;
                        }

                        JsonNode root = objectMapper.readTree(data);
                        JsonNode deltaNode = root.at("/choices/0/delta/content");
                        String chunk = extractTextContent(deltaNode);
                        if (chunk.isBlank()) {
                            continue;
                        }

                        contentBuilder.append(chunk);
                        if (onChunk != null) {
                            onChunk.accept(chunk);
                        }
                    }

                    String json = extractJsonBlock(contentBuilder.toString());
                    if (json == null) {
                        LOGGER.warn("LLM streaming response did not contain valid JSON: {}", contentBuilder);
                        return Optional.empty();
                    }

                    return Optional.of(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
                    }));
                }
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("LLM request failed with status {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode contentNode = root.at("/choices/0/message/content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                LOGGER.warn("LLM response missing content: {}", response.body());
                return Optional.empty();
            }

            String content = extractTextContent(contentNode);
            String json = extractJsonBlock(content);
            if (json == null) {
                LOGGER.warn("LLM response did not contain valid JSON: {}", content);
                return Optional.empty();
            }

            return Optional.of(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (InterruptedException error) {
            LOGGER.warn("LLM request interrupted", error);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException error) {
            LOGGER.warn("LLM request returned invalid payload", error);
            return Optional.empty();
        } catch (Exception error) {
            LOGGER.warn("LLM request failed", error);
            return Optional.empty();
        }
    }

    private Optional<Map<String, Object>> requestTextStructuredJson(
            String systemPrompt,
            String userPrompt
    ) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getModel());
            body.put("temperature", 0.5);
            body.put("max_tokens", 900);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
            ));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(properties.getBaseUrl()) + "/chat/completions"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

            if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
                builder.header("authorization", "Bearer " + properties.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("LLM text request failed with status {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode contentNode = root.at("/choices/0/message/content");
            if (contentNode.isMissingNode() || contentNode.isNull()) {
                LOGGER.warn("LLM text response missing content: {}", response.body());
                return Optional.empty();
            }

            String content = extractTextContent(contentNode);
            String json = extractJsonBlock(content);
            if (json == null) {
                LOGGER.warn("LLM text response did not contain valid JSON: {}", content);
                return Optional.empty();
            }

            return Optional.of(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            }));
        } catch (InterruptedException error) {
            LOGGER.warn("LLM text request interrupted", error);
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException error) {
            LOGGER.warn("LLM text request returned invalid payload", error);
            return Optional.empty();
        } catch (Exception error) {
            LOGGER.warn("LLM text request failed", error);
            return Optional.empty();
        }
    }

    private String buildVisionPrompt(String scene) {
        return """
                你是视障辅助系统的多模态识图助手。请根据图片和场景，只输出一个 JSON 对象，不能输出任何解释、代码块或额外文字。
                JSON 结构固定如下：
                {
                  "recognizedText": "40到90字的中文短描述，必须非空，优先说明前方是否可通行、障碍物和方向",
                  "voiceBroadcast": "25到60字的中文播报短句，必须非空，可直接朗读",
                  "safetyLevel": "只能是 low、medium 或 high 三选一",
                  "sceneTips": ["每项不超过18字", "总数固定3项", "强调行动建议"]
                }
                如果图像里无法完全确认，也要基于可见内容给出保守且可执行的中文提示，禁止留空字段。
                当前场景：%s
                """.formatted(scene);
    }

    private String buildTextReadingPrompt() {
        return """
                你是视障辅助系统的文本阅读助手。请根据图片内容，只输出一个 JSON 对象，不能输出解释、代码块或任何额外文字。
                任务目标：
                1. 尽可能识别图片中全部可见文字，按自然阅读顺序输出，不能总结代替原文。
                2. 如果原图是书页、小说、教材、菜单、告示、包装或说明书，也要把能看清的正文尽量完整提取出来。
                3. readingText 和 voiceBroadcast 要在不改变原意、不新增事实的前提下，补全必要标点、合并断行、优化断句，适合语音朗读。
                4. 如果局部看不清，可以保守处理，但不要编造缺失内容。
                JSON 结构固定如下：
                {
                  "recognizedText": "按阅读顺序提取出的全文，尽量保留自然段和换行，不能为空",
                  "readingText": "在忠实原文的前提下整理后的朗读文本，可补全标点和段落，不能为空",
                  "voiceBroadcast": "可直接用于 TTS 朗读的文本，通常与 readingText 接近，不能为空",
                  "safetyLevel": "只允许 low、medium、high，文本阅读场景默认 low",
                  "sceneTips": ["固定 3 条", "每条不超过 18 个字", "强调拍摄和朗读建议"]
                }
                如果画面里文字较多，请优先保证正文完整，不要把输出写成摘要。
                当前场景：text-reading
                """;
    }

    private String buildNewsPrompt(String city, int count, List<String> existingTitles) {
        String titleBlock = existingTitles == null || existingTitles.isEmpty()
                ? "无"
                : String.join("；", existingTitles);

        return """
                请为 %s 生成 %s 条适合首页推送的中文资讯，只输出一个 JSON 对象。
                JSON 结构固定如下：
                {
                  "items": [
                    {
                      "category": "只能是 出行提醒、社区资讯、民生快讯 三选一",
                      "title": "16到28字，必须非空",
                      "summary": "24到56字，必须非空，强调可执行建议",
                      "content": "40到100字，必须非空，用更完整的话说明为什么值得关注",
                      "spokenText": "25到60字，必须非空，可直接朗读"
                    }
                  ]
                }
                要求：
                1. 每条都要适合视障人士直接收听和行动。
                2. 不要生成天气、气温、空气质量、天气预报或灾害预警相关内容，这些已经由独立天气模块承担。
                3. 如果不是已核实的实时新闻，必须写成辅助提醒或出行建议，不能伪装成真实公告。
                4. 标题不要和已有内容重复。
                5. 三个分类尽量都覆盖，至少覆盖两个分类。
                已有标题：%s
                """.formatted(city, count, titleBlock);
    }

    private boolean isTextReadingScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return false;
        }
        return switch (scene.trim()) {
            case "text-reading", "text_reading", "textReading", "reading", "ocr" -> true;
            default -> false;
        };
    }

    private int clampVisionMaxTokens(String model, int requested) {
        if (requested <= 0) {
            return requested;
        }

        int limit = switch (model == null ? "" : model.trim().toLowerCase()) {
            case "glm-4v-flash" -> 1024;
            case "glm-4.5v" -> 16_384;
            case "glm-4.6v", "glm-4.6v-flash", "glm-4.6v-flashx",
                    "glm-4.1v-thinking-flash", "glm-4.1v-thinking-flashx" -> 32_768;
            case "glm-5v-turbo" -> 131_072;
            default -> Integer.MAX_VALUE;
        };

        if (requested > limit) {
            LOGGER.info("vision max_tokens clamped model={} requested={} effective={}", model, requested, limit);
            return limit;
        }
        return requested;
    }

    private List<Map<String, Object>> buildMessages(String systemPrompt, String userPrompt, byte[] imageBytes, String mimeType) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userPrompt));
        userContent.add(Map.of(
                "type", "image_url",
                "image_url", Map.of(
                        "url", "data:%s;base64,%s".formatted(
                                mimeType == null || mimeType.isBlank() ? "image/jpeg" : mimeType,
                                Base64.getEncoder().encodeToString(imageBytes)
                        )
                )
        ));
        messages.add(Map.of("role", "user", "content", userContent));
        return messages;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String extractTextContent(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            StringBuilder builder = new StringBuilder();
            for (JsonNode item : node) {
                JsonNode textNode = item.get("text");
                if (textNode != null && textNode.isTextual()) {
                    builder.append(textNode.asText());
                }
            }
            return builder.toString();
        }
        return node.toString();
    }

    private String extractJsonBlock(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }
}
