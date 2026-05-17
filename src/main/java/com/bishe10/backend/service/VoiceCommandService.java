package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.dto.VoiceCommandRequest;
import com.bishe10.backend.dto.VoiceCommandResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VoiceCommandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceCommandService.class);
    private static final Set<String> SUPPORTED_INTENTS = Set.of(
            "play_news",
            "stop_play",
            "refresh_city",
            "open_city_picker",
            "dislike_news",
            "like_news",
            "open_vision",
            "take_photo",
            "read_detail",
            "next_page",
            "prev_page",
            "go_home",
            "go_profile",
            "unknown"
    );
    private static final Map<String, Integer> CHINESE_INDEXES = Map.ofEntries(
            Map.entry("一", 1),
            Map.entry("二", 2),
            Map.entry("两", 2),
            Map.entry("三", 3),
            Map.entry("四", 4),
            Map.entry("五", 5),
            Map.entry("六", 6),
            Map.entry("七", 7),
            Map.entry("八", 8),
            Map.entry("九", 9),
            Map.entry("十", 10)
    );
    private static final List<String> KNOWN_CITIES = List.of(
            "北京", "上海", "广州", "深圳", "南京", "杭州", "武汉", "成都", "重庆", "西安",
            "苏州", "天津", "厦门", "青岛", "长沙", "沈阳", "大连", "济南", "郑州", "合肥",
            "镇江", "无锡", "常州", "宁波", "福州", "南昌", "昆明", "贵阳", "太原", "石家庄"
    );
    private static final Pattern DIGIT_INDEX_PATTERN = Pattern.compile("第\\s*(\\d+)\\s*(条|个|篇|则)?");
    private static final Pattern CITY_AFTER_VERB_PATTERN = Pattern.compile("(换到|切换到|改到|定位到|城市设为|城市切到)\\s*([\\p{IsHan}]{2,8})(市)?");

    private final Bishe10Properties.Llm properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VoiceCommandService(Bishe10Properties bishe10Properties, ObjectMapper objectMapper) {
        this.properties = bishe10Properties.getLlm();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public VoiceCommandResponse understand(VoiceCommandRequest request) {
        VoiceCommandRequest safeRequest = request == null ? new VoiceCommandRequest() : request;
        String text = cleanText(safeRequest.getText());
        if (text.isBlank()) {
            return response("unknown", Map.of(), "请先说出要执行的语音指令");
        }

        VoiceCommandResponse localMatch = fallbackMatch(text);
        if (!"unknown".equals(localMatch.getIntent())) {
            return localMatch;
        }

        if (isConfigured()) {
            try {
                return parseResponse(callLlm(safeRequest, text));
            } catch (Exception error) {
                LOGGER.warn("voice command LLM failed, falling back to local rules", error);
            }
        }
        return localMatch;
    }

    private boolean isConfigured() {
        return properties.isEnabled()
                && hasText(properties.getBaseUrl())
                && hasText(properties.getApiKey())
                && hasText(properties.getModel());
    }

    private String callLlm(VoiceCommandRequest request, String text) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", 0.1);
        body.put("max_tokens", 220);
        body.put("messages", List.of(
                Map.of("role", "system", "content", buildSystemPrompt(request)),
                Map.of("role", "user", "content", text)
        ));

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(properties.getBaseUrl()) + "/chat/completions"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(commandTimeoutSeconds()))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));

        if (hasText(properties.getApiKey())) {
            builder.header("authorization", "Bearer " + properties.getApiKey());
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("LLM voice command request failed with status " + response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (contentNode.isMissingNode() || contentNode.isNull()) {
            throw new IOException("LLM voice command response missing content");
        }
        return contentNode.isTextual() ? contentNode.asText() : contentNode.toString();
    }

    private int commandTimeoutSeconds() {
        int configured = properties.getTimeoutSeconds() <= 0 ? 10 : properties.getTimeoutSeconds();
        return Math.max(2, Math.min(configured, 10));
    }

    private String buildSystemPrompt(VoiceCommandRequest request) {
        String page = cleanText(request.getPage());
        String city = cleanText(request.getCity());
        int newsCount = request.getNewsCount() == null ? 0 : Math.max(0, request.getNewsCount());
        return """
                你是视障人士智能语音助手。你的任务是把用户的中文自然语言指令转成一个 JSON 对象，不能输出解释、代码块或多余文字。

                当前页面：%s
                当前城市：%s
                当前新闻数：%d

                只允许使用这些 intent：
                - play_news：朗读新闻。params.index 表示第几条，没说第几条时不要放 index
                - stop_play：停止朗读
                - refresh_city：切换城市或刷新当前城市资讯。切换城市时 params.cityName 必须是城市名
                - open_city_picker：打开城市选择器
                - dislike_news：不喜欢某条新闻。params.index 表示第几条，没说时默认第一条
                - like_news：喜欢某条新闻。params.index 表示第几条，没说时默认第一条
                - open_vision：打开识图功能
                - take_photo：拍照并识图
                - read_detail：打开或朗读新闻详情。params.index 表示第几条，没说时默认第一条
                - next_page：下一条、下一页或加载更多
                - prev_page：上一条
                - go_home：回首页
                - go_profile：个人中心
                - unknown：无法理解

                JSON 格式固定如下：
                {"intent":"play_news","params":{"index":2},"reply":"好的，为您播报第二条新闻"}
                """.formatted(
                page.isBlank() ? "未知" : page,
                city.isBlank() ? "未知" : city,
                newsCount
        );
    }

    private VoiceCommandResponse parseResponse(String llmText) {
        String json = extractJsonBlock(llmText);
        if (json == null) {
            return fallbackMatch(llmText);
        }

        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            String intent = map.get("intent") instanceof String value ? value.trim() : "unknown";
            if (!SUPPORTED_INTENTS.contains(intent)) {
                intent = "unknown";
            }

            Map<String, Object> params = map.get("params") instanceof Map<?, ?> rawParams
                    ? normalizeParams(rawParams)
                    : Map.of();
            String reply = map.get("reply") instanceof String value && !value.isBlank()
                    ? value.trim()
                    : "好的";
            return response(intent, params, reply);
        } catch (Exception error) {
            LOGGER.warn("failed to parse voice command LLM JSON: {}", llmText, error);
            return fallbackMatch(llmText);
        }
    }

    private Map<String, Object> normalizeParams(Map<?, ?> rawParams) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawParams.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank()) {
                continue;
            }
            Object value = entry.getValue();
            if ("index".equals(key)) {
                Integer index = normalizeIndex(value);
                if (index != null) {
                    params.put(key, index);
                }
                continue;
            }
            if (value instanceof String text) {
                String cleaned = cleanText(text);
                if (!cleaned.isBlank()) {
                    params.put(key, cleaned);
                }
                continue;
            }
            if (value != null) {
                params.put(key, value);
            }
        }
        return params;
    }

    private Integer normalizeIndex(Object value) {
        if (value instanceof Number number) {
            int index = number.intValue();
            return index > 0 ? index : null;
        }
        if (value instanceof String text) {
            String cleaned = cleanText(text);
            try {
                int index = Integer.parseInt(cleaned);
                return index > 0 ? index : null;
            } catch (NumberFormatException ignored) {
                return CHINESE_INDEXES.get(cleaned);
            }
        }
        return null;
    }

    private VoiceCommandResponse fallbackMatch(String rawText) {
        String text = cleanText(rawText);
        if (text.isBlank()) {
            return response("unknown", Map.of(), "请先说出要执行的语音指令");
        }

        Optional<Integer> index = extractIndex(text);
        Optional<String> city = extractCityName(text);

        if (contains(text, "停止", "停下", "暂停", "别读", "关掉", "静音", "别播", "不要播", "太吵")) {
            return response("stop_play", Map.of(), "已停止");
        }
        if (contains(text, "拍照", "拍一张", "照一下", "打开相机", "摄像头")) {
            return response("take_photo", Map.of(), "打开摄像头");
        }
        if (contains(text, "识图", "识别", "看看前面", "看前面", "帮我看看", "前面有什么")) {
            return response("open_vision", Map.of(), "打开识图功能");
        }
        if (contains(text, "不喜欢", "不要这条", "不想看", "不感兴趣", "不好看")) {
            return response("dislike_news", Map.of("index", index.orElse(1)), "已帮您标记，以后少推荐");
        }
        if (contains(text, "喜欢", "点赞", "不错", "好看", "收藏")) {
            return response("like_news", Map.of("index", index.orElse(1)), "感谢反馈");
        }
        if (city.isPresent()) {
            return response("refresh_city", Map.of("cityName", city.get()), "已切换到" + city.get());
        }
        if (contains(text, "城市", "切换城市", "换个地方", "选择城市")) {
            return response("open_city_picker", Map.of(), "请选择城市");
        }
        if (contains(text, "刷新", "重新加载", "更新一下", "换一批")) {
            return response("refresh_city", Map.of(), "已刷新");
        }
        if (contains(text, "个人", "我的", "账户", "账号", "中心", "信息")) {
            return response("go_profile", Map.of(), "打开个人中心");
        }
        if (contains(text, "首页", "主页", "回首页", "返回首页")) {
            return response("go_home", Map.of(), "回到首页");
        }
        if (contains(text, "下一条", "下一个", "下一页", "加载更多")) {
            return response("next_page", Map.of(), "好的");
        }
        if (contains(text, "上一条", "上一个", "上一页")) {
            return response("prev_page", Map.of(), "好的");
        }
        if (contains(text, "详情", "详细", "正文")) {
            return response("read_detail", Map.of("index", index.orElse(1)), "打开资讯详情");
        }
        if (contains(text, "播放", "朗读", "播报", "听新闻", "听听", "读一下", "念一下", "播一下")) {
            Map<String, Object> params = index.map(value -> Map.<String, Object>of("index", value)).orElseGet(Map::of);
            return response("play_news", params, index.map(value -> "好的，为您播报第" + value + "条新闻").orElse("好的，为您播报新闻"));
        }

        return response("unknown", Map.of(), "抱歉，我没理解。您可以试着说：播报新闻、拍照识图、换个城市");
    }

    private Optional<Integer> extractIndex(String text) {
        Matcher digitMatcher = DIGIT_INDEX_PATTERN.matcher(text);
        if (digitMatcher.find()) {
            try {
                int value = Integer.parseInt(digitMatcher.group(1));
                if (value > 0) {
                    return Optional.of(value);
                }
            } catch (NumberFormatException ignored) {
            }
        }

        for (Map.Entry<String, Integer> entry : CHINESE_INDEXES.entrySet()) {
            if (text.contains("第" + entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<String> extractCityName(String text) {
        Matcher matcher = CITY_AFTER_VERB_PATTERN.matcher(text);
        if (matcher.find()) {
            String city = matcher.group(2);
            if (city != null && !city.isBlank()) {
                return Optional.of(normalizeCity(city));
            }
        }

        return KNOWN_CITIES.stream()
                .filter(text::contains)
                .findFirst()
                .map(this::normalizeCity);
    }

    private String normalizeCity(String city) {
        String value = cleanText(city);
        return value.endsWith("市") ? value.substring(0, value.length() - 1) : value;
    }

    private boolean contains(String text, String... keys) {
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private VoiceCommandResponse response(String intent, Map<String, Object> params, String reply) {
        VoiceCommandResponse response = new VoiceCommandResponse();
        response.setIntent(intent);
        response.setParams(params == null ? Map.of() : params);
        response.setReply(reply == null || reply.isBlank() ? "好的" : reply);
        return response;
    }

    private String cleanText(String text) {
        return text == null ? "" : text.trim();
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = cleanText(baseUrl);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String extractJsonBlock(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim()
                .replaceFirst("^```json\\s*", "")
                .replaceFirst("^```\\s*", "")
                .replaceFirst("\\s*```$", "");
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }
}
