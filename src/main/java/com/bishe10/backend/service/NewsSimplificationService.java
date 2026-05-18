package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.dto.NewsSimplificationRequest;
import com.bishe10.backend.dto.NewsSimplificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class NewsSimplificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewsSimplificationService.class);
    private static final int MAX_ITEMS = 40;
    private static final int MAX_FIELD_CHARS = 260;
    private static final int TARGET_CHARS = 30;

    private final Bishe10Properties.Llm properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Map<String, NewsSimplificationResponse.Item> cache = new ConcurrentHashMap<>();

    public NewsSimplificationService(Bishe10Properties bishe10Properties, ObjectMapper objectMapper) {
        this.properties = bishe10Properties.getLlm();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.max(5, properties.getTimeoutSeconds())))
                .build();
    }

    public NewsSimplificationResponse simplify(NewsSimplificationRequest request) {
        String city = clean(request == null ? "" : request.city());
        List<SimplifyInput> inputs = sanitizeItems(request == null ? List.of() : request.items());
        if (inputs.isEmpty()) {
            return new NewsSimplificationResponse(city, providerLabel(), "empty", 0, List.of());
        }

        Map<String, NewsSimplificationResponse.Item> prepared = new LinkedHashMap<>();
        List<SimplifyInput> missing = new ArrayList<>();
        for (SimplifyInput input : inputs) {
            NewsSimplificationResponse.Item cached = cache.get(input.cacheKey());
            if (cached == null) {
                missing.add(input);
            } else {
                prepared.put(input.id(), cached);
            }
        }

        if (!missing.isEmpty()) {
            Map<String, String> llmItems = requestLlm(city, missing).orElse(Map.of());
            for (SimplifyInput input : missing) {
                String simplified = normalizeSummary(llmItems.get(input.id()));
                NewsSimplificationResponse.Item item = simplified.isBlank()
                        ? fallbackItem(input)
                        : new NewsSimplificationResponse.Item(
                                input.id(),
                                input.title(),
                                simplified,
                                buildSpokenText(input.title(), simplified),
                                "llm"
                        );
                cache.put(input.cacheKey(), item);
                prepared.put(input.id(), item);
            }
        }

        List<NewsSimplificationResponse.Item> ordered = inputs.stream()
                .map(input -> prepared.getOrDefault(input.id(), fallbackItem(input)))
                .toList();
        String mode = responseMode(ordered);
        return new NewsSimplificationResponse(city, providerLabel(), mode, inputs.size(), ordered);
    }

    private Optional<Map<String, String>> requestLlm(String city, List<SimplifyInput> inputs) {
        if (!isConfigured() || inputs.isEmpty()) {
            return Optional.empty();
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", properties.getModel());
            body.put("temperature", 0.2);
            body.put("max_tokens", Math.min(1800, 240 + inputs.size() * 80));
            body.put("messages", List.of(
                    Map.of("role", "system", "content", buildSystemPrompt()),
                    Map.of("role", "user", "content", buildUserPrompt(city, inputs))
            ));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(properties.getBaseUrl()) + "/chat/completions"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(Math.max(10, properties.getTimeoutSeconds())))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

            if (hasText(properties.getApiKey())) {
                builder.header("authorization", "Bearer " + properties.getApiKey());
            }

            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("news simplification LLM failed status={} body={}", response.statusCode(), response.body());
                return Optional.empty();
            }

            JsonNode contentNode = objectMapper.readTree(response.body()).at("/choices/0/message/content");
            String json = extractJsonBlock(extractTextContent(contentNode));
            if (json == null) {
                LOGGER.warn("news simplification LLM returned non-json content");
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(json);
            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                return Optional.empty();
            }

            Map<String, String> result = new LinkedHashMap<>();
            for (JsonNode node : items) {
                String id = textOf(node.get("id"));
                String simplified = firstNonBlank(
                        textOf(node.get("simplifiedText")),
                        textOf(node.get("summary")),
                        textOf(node.get("spokenText"))
                );
                if (hasText(id) && hasText(simplified)) {
                    result.put(id, simplified);
                }
            }
            return Optional.of(result);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.warn("news simplification LLM interrupted", error);
            return Optional.empty();
        } catch (IOException error) {
            LOGGER.warn("news simplification LLM IO failed", error);
            return Optional.empty();
        } catch (Exception error) {
            LOGGER.warn("news simplification LLM failed", error);
            return Optional.empty();
        }
    }

    private String buildSystemPrompt() {
        return """
                你是新闻语音播报压缩助手，服务对象是视障用户。
                只输出一个 JSON 对象，不能输出解释、代码块或额外文字。
                必须逐条处理输入新闻，不能合并多条新闻，不能新增事实，不能编造时间、地点或结论。
                每条 simplifiedText 尽量控制在 25 到 35 个中文字，保留核心事件和对用户最有用的信息。
                JSON 结构固定如下：
                {
                  "items": [
                    {"id": "输入中的 id", "simplifiedText": "约30字中文简讯", "spokenText": "可直接朗读的同义短句"}
                  ]
                }
                """;
    }

    private String buildUserPrompt(String city, List<SimplifyInput> inputs) throws IOException {
        List<Map<String, Object>> payload = inputs.stream()
                .map(input -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", input.id());
                    item.put("title", input.title());
                    item.put("summary", input.summary());
                    item.put("content", input.content());
                    item.put("source", input.source());
                    item.put("category", input.category());
                    return item;
                })
                .collect(Collectors.toList());
        return """
                城市：%s
                请把下面每条新闻分别压缩成约30个中文字的简讯，并保持 items 数量和 id 对应关系。
                新闻列表 JSON：
                %s
                """.formatted(city, objectMapper.writeValueAsString(payload));
    }

    private List<SimplifyInput> sanitizeItems(List<NewsSimplificationRequest.Item> rawItems) {
        if (rawItems == null || rawItems.isEmpty()) {
            return List.of();
        }

        List<SimplifyInput> result = new ArrayList<>();
        int index = 0;
        for (NewsSimplificationRequest.Item item : rawItems) {
            if (item == null || result.size() >= MAX_ITEMS) {
                continue;
            }
            String title = trimToChars(clean(item.title()), MAX_FIELD_CHARS);
            String summary = trimToChars(clean(item.summary()), MAX_FIELD_CHARS);
            String content = trimToChars(clean(item.content()), MAX_FIELD_CHARS);
            if (!hasText(title) && !hasText(summary) && !hasText(content)) {
                continue;
            }
            String id = clean(item.id());
            if (!hasText(id)) {
                id = "news-" + index;
            }
            result.add(new SimplifyInput(
                    id,
                    title,
                    summary,
                    content,
                    trimToChars(clean(item.source()), 80),
                    trimToChars(clean(item.category()), 40),
                    hash(id + "|" + title + "|" + summary + "|" + content)
            ));
            index++;
        }
        return result;
    }

    private NewsSimplificationResponse.Item fallbackItem(SimplifyInput input) {
        String base = firstNonBlank(input.summary(), input.content(), input.title());
        String simplified = normalizeSummary(base);
        if (simplified.isBlank()) {
            simplified = "这条资讯暂无可简化内容";
        }
        return new NewsSimplificationResponse.Item(
                input.id(),
                input.title(),
                simplified,
                buildSpokenText(input.title(), simplified),
                "fallback"
        );
    }

    private String normalizeSummary(String value) {
        String cleaned = clean(value)
                .replaceAll("https?://\\S+", "")
                .replaceAll("[。；;]+$", "");
        return trimToChars(cleaned, TARGET_CHARS);
    }

    private String buildSpokenText(String title, String simplified) {
        if (!hasText(title)) {
            return simplified;
        }
        String cleanTitle = clean(title);
        if (simplified.startsWith(cleanTitle) || cleanTitle.startsWith(simplified)) {
            return simplified;
        }
        return simplified;
    }

    private String responseMode(List<NewsSimplificationResponse.Item> items) {
        boolean hasLlm = false;
        boolean hasFallback = false;
        for (NewsSimplificationResponse.Item item : items) {
            if ("llm".equals(item.mode())) {
                hasLlm = true;
            } else {
                hasFallback = true;
            }
        }
        if (hasLlm && hasFallback) {
            return "mixed";
        }
        return hasLlm ? "llm" : "fallback";
    }

    private boolean isConfigured() {
        return properties.isEnabled()
                && hasText(properties.getBaseUrl())
                && hasText(properties.getApiKey())
                && hasText(properties.getModel());
    }

    private String providerLabel() {
        return hasText(properties.getProviderLabel()) ? properties.getProviderLabel() : "DeepSeek";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String trimToChars(String value, int maxChars) {
        if (!hasText(value) || maxChars <= 0) {
            return "";
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxChars) {
            return value;
        }
        int end = value.offsetByCodePoints(0, Math.max(1, maxChars - 1));
        return value.substring(0, end).trim() + "…";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String textOf(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText().trim();
    }

    private String extractTextContent(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return "";
        }
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
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return raw.substring(start, end + 1);
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < Math.min(16, bytes.length); i++) {
                builder.append(String.format(Locale.ROOT, "%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception error) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private record SimplifyInput(
            String id,
            String title,
            String summary,
            String content,
            String source,
            String category,
            String cacheKey
    ) {
    }
}
