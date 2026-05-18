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
    private static final String NEWS_PAGE_REQUIRED_REPLY = "请先打开资讯界面后再操作新闻";
    private static final Set<String> SUPPORTED_INTENTS = Set.of(
            "play_news",
            "stop_play",
            "refresh_city",
            "open_city_picker",
            "dislike_news",
            "like_news",
            "skip_news",
            "open_vision",
            "take_photo",
            "choose_vision_image",
            "set_vision_scene",
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
    private static final Pattern DIGIT_INDEX_PATTERN = Pattern.compile("(?:第\\s*)?(\\d+)\\s*(条|个|篇|则)");
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

        VoiceCommandResponse localMatch = fallbackMatch(text, safeRequest.getPage());
        if (!"unknown".equals(localMatch.getIntent()) || NEWS_PAGE_REQUIRED_REPLY.equals(localMatch.getReply())) {
            return localMatch;
        }

        if (isConfigured()) {
            try {
                return reconcileWithOriginalText(text, safeRequest.getPage(), parseResponse(callLlm(safeRequest, text)));
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
                - play_news：朗读新闻。只有用户明确说“播报、朗读、播放、听新闻、听听新闻、读一下新闻”等朗读动作时才使用。params.index 表示第几条，没说第几条时不要放 index
                - stop_play：停止朗读
                - refresh_city：切换城市或刷新当前城市资讯。切换城市时 params.cityName 必须是城市名
                - open_city_picker：打开城市选择器
                - dislike_news：不喜欢某条新闻。params.index 表示第几条，没说时默认第一条
                - like_news：喜欢某条新闻。params.index 表示第几条，没说时默认第一条
                - skip_news：跳过某条新闻。params.index 表示第几条，没说时默认第一条
                - open_vision：打开识图界面
                - take_photo：拍照并识图
                - choose_vision_image：在识图界面打开选图/上传图片入口
                - set_vision_scene：切换识图场景。params.scene 只能是 general、crossroad、supermarket、text-reading；用户说“我想识别十字路口/超市/文本/通用识别、我想读读书、帮我读文字”时使用，并设置 params.openPicker=true
                - read_detail：打开或朗读新闻详情。params.index 表示第几条，没说时默认第一条
                - next_page：下一条、下一页或加载更多
                - prev_page：上一条
                - go_home：回首页，或打开首页资讯/新闻资讯列表。用户说“打开资讯界面、进入新闻页面、切换到资讯页、看资讯、我想看看新闻”时必须使用 go_home，不要使用 play_news
                - go_profile：个人中心
                - unknown：无法理解

                JSON 格式固定如下：
                {"intent":"play_news","params":{"index":2},"reply":"好的，为您播报第二条新闻"}

                判定优先级：
                1. “打开/进入/切换/回到 + 资讯/新闻 + 页面/界面/列表”是页面跳转，intent 必须是 go_home。
                2. “打开/进入/切换到 + 识图/识别 + 页面/界面”是页面跳转，intent 必须是 open_vision。
                3. “我想识别十字路口/超市/文本/通用识别、我想读读书、我想看书”是识图场景切换，intent 必须是 set_vision_scene，并带 scene 和 openPicker=true。
                4. “听新闻/听听新闻/播报新闻/朗读新闻/播放新闻”才是朗读新闻，intent 才能是 play_news；“看新闻/看看新闻”是打开资讯，不是朗读。
                5. “打开新闻详情/正文/第几条详情”是 read_detail，不是 play_news。
                6. 首页新闻操作只在当前页面是 home 时执行；当前页面不是 home 且用户没有明确要求打开资讯界面时，不要把喜欢、不喜欢、跳过、刷新城市新闻误判成识图播报。
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
            if ("scene".equals(key)) {
                Optional<String> scene = normalizeVisionScene(String.valueOf(value));
                scene.ifPresent(normalized -> params.put(key, normalized));
                continue;
            }
            if ("openPicker".equals(key)) {
                if (value instanceof Boolean bool) {
                    params.put(key, bool);
                } else if (value instanceof String text) {
                    params.put(key, "true".equalsIgnoreCase(text.trim()) || "是".equals(text.trim()));
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
        return fallbackMatch(rawText, "");
    }

    private VoiceCommandResponse fallbackMatch(String rawText, String rawPage) {
        String text = cleanText(rawText);
        String page = cleanText(rawPage);
        if (text.isBlank()) {
            return response("unknown", Map.of(), "请先说出要执行的语音指令");
        }

        Optional<Integer> index = extractIndex(text);
        Optional<String> city = extractCityName(text);
        Optional<String> visionScene = extractVisionScene(text);
        boolean homePage = isHomePage(page);
        boolean visionPage = isVisionPage(page);

        if (contains(text, "停止", "停下", "暂停", "别读", "关掉", "静音", "别播", "不要播", "太吵")) {
            return response("stop_play", Map.of(), "已停止");
        }
        if (isIndexedNewsDetailRequest(text) && homePage) {
            return response("read_detail", Map.of("index", index.orElse(1)), "打开资讯详情");
        }
        if (isNewsHomeNavigation(text)) {
            return response("go_home", Map.of(), "打开城市资讯");
        }
        if (isProfileNavigation(text)) {
            return response("go_profile", Map.of(), "打开个人中心");
        }
        if (visionScene.isPresent()) {
            return response("set_vision_scene", Map.of("scene", visionScene.get(), "openPicker", true),
                    "切换到" + visionSceneLabel(visionScene.get()) + "识别");
        }
        if (isVisionNavigation(text)) {
            return response("open_vision", Map.of(), "打开识图界面");
        }
        if (contains(text, "选图", "选择图片", "上传图片", "打开图片", "打开相册", "从相册选", "选一张图", "选照片")) {
            return response("choose_vision_image", Map.of(), "打开选图界面");
        }
        if (contains(text, "拍照", "拍一张", "照一下", "打开相机", "摄像头")) {
            return response("take_photo", Map.of(), "打开摄像头");
        }
        if (visionPage
                && !contains(text, "播报", "朗读", "播放", "播一下", "读一下", "念一下", "听新闻", "听资讯", "听听")
                && isForwardVisionRequest(text)) {
            return response("choose_vision_image", Map.of(), "打开选图界面");
        }
        if (isForwardVisionRequest(text)) {
            return response("open_vision", Map.of(), "打开识图界面");
        }

        if (!homePage && (city.isPresent() || isNewsOnlyOperation(text))) {
            return response("unknown", Map.of(), NEWS_PAGE_REQUIRED_REPLY);
        }
        if (contains(text, "不喜欢", "不要这条", "不想看", "不感兴趣", "不好看")) {
            return response("dislike_news", Map.of("index", index.orElse(1)), "已帮您标记，以后少推荐");
        }
        if (contains(text, "喜欢", "点赞", "不错", "好看", "收藏")) {
            return response("like_news", Map.of("index", index.orElse(1)), "感谢反馈");
        }
        if (contains(text, "跳过", "跳过这条", "跳过新闻", "略过", "下一条新闻", "不要看这条")) {
            return response("skip_news", Map.of("index", index.orElse(1)), "已跳过这条资讯");
        }
        if (city.isPresent()) {
            return response("refresh_city", Map.of("cityName", city.get()), "已切换到" + city.get());
        }
        if (contains(text, "刷新", "重新加载", "更新一下", "换一批")) {
            return response("refresh_city", Map.of(), "已刷新");
        }
        if (contains(text, "切换城市", "城市选择", "选择城市", "换个城市", "换个地方")) {
            return response("open_city_picker", Map.of(), "请选择城市");
        }
        if (contains(text, "详情", "详细", "正文")) {
            return response("read_detail", Map.of("index", index.orElse(1)), "打开资讯详情");
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
        if (isNewsPlayback(text)) {
            Map<String, Object> params = index.map(value -> Map.<String, Object>of("index", value)).orElseGet(Map::of);
            return response("play_news", params, index.map(value -> "好的，为您播报第" + value + "条新闻").orElse("好的，为您播报新闻"));
        }

        return response("unknown", Map.of(), "抱歉，我没理解。您可以试着说：播报新闻、拍照识图、换个城市");
    }

    private VoiceCommandResponse reconcileWithOriginalText(String text, String page, VoiceCommandResponse response) {
        if (response == null) {
            return fallbackMatch(text, page);
        }
        Optional<String> visionScene = extractVisionScene(text);
        if (visionScene.isPresent() && ("open_vision".equals(response.getIntent()) || "unknown".equals(response.getIntent()))) {
            return response("set_vision_scene", Map.of("scene", visionScene.get(), "openPicker", true),
                    "切换到" + visionSceneLabel(visionScene.get()) + "识别");
        }
        if ("play_news".equals(response.getIntent()) && isNewsHomeNavigation(text)) {
            return response("go_home", Map.of(), "打开城市资讯");
        }
        if (!isHomePage(cleanText(page)) && (extractCityName(text).isPresent() || isNewsOnlyOperation(text))
                && contains(response.getIntent(), "play_news", "refresh_city", "like_news", "dislike_news", "skip_news", "read_detail")) {
            return response("unknown", Map.of(), NEWS_PAGE_REQUIRED_REPLY);
        }
        return response;
    }

    private boolean isIndexedNewsDetailRequest(String text) {
        return extractIndex(text).isPresent()
                && contains(text, "新闻", "资讯", "快讯", "这条", "那条")
                && contains(text, "看", "看看", "查看", "打开", "点开", "进入");
    }

    private boolean isNewsHomeNavigation(String text) {
        if (text.isBlank() || contains(text, "详情", "详细", "正文")) {
            return false;
        }
        boolean mentionsNews = contains(text, "资讯", "咨询", "咨讯", "新闻", "快讯", "本地资讯", "城市资讯", "推荐");
        if (!mentionsNews) {
            return false;
        }
        if (isNewsPlayback(text)) {
            return false;
        }
        boolean navigationAction = contains(text,
                "打开", "进入", "切换", "跳转", "返回", "回到", "回", "去", "到",
                "查看", "看看", "看一下", "想看", "想看看", "看资讯", "看新闻", "浏览", "翻翻", "逛逛");
        boolean pageTarget = contains(text,
                "界面", "页面", "页", "列表", "栏目", "频道", "模块", "功能", "首页", "主页", "中心");
        return navigationAction || pageTarget;
    }

    private boolean isProfileNavigation(String text) {
        return contains(text, "个人中心", "我的", "账户", "账号", "个人", "资料", "设置", "偏好");
    }

    private boolean isVisionNavigation(String text) {
        boolean mentionsVision = contains(text, "识图", "识别", "视觉", "看图");
        if (!mentionsVision || contains(text, "十字路口", "十足路口", "路口", "超市", "货架", "文本", "文字", "通用")) {
            return false;
        }
        boolean navigationAction = contains(text, "打开", "进入", "切换", "跳转", "返回", "回到", "去", "到");
        boolean pageTarget = contains(text, "界面", "页面", "页", "功能", "模块");
        return navigationAction || pageTarget;
    }

    private boolean isForwardVisionRequest(String text) {
        return contains(text,
                "识图", "识别", "看看前面", "看前面", "看一下前面", "想看看前面",
                "看看前方", "看前方", "看一下前方", "前面有什么", "前方有什么",
                "眼前有什么", "周围有什么", "帮我看看", "帮我看一下", "看看路况", "看看环境");
    }

    private boolean isNewsPlayback(String text) {
        if (contains(text,
                "播放新闻", "播放资讯", "朗读新闻", "朗读资讯", "播报新闻", "播报资讯",
                "听新闻", "听资讯", "听听新闻", "听听资讯", "听一下新闻", "听一下资讯",
                "想听新闻", "想听资讯", "想听听新闻", "想听听资讯",
                "读新闻", "读资讯", "读一下新闻", "读一下资讯", "念新闻", "念资讯", "播一下新闻", "播一下资讯")) {
            return true;
        }
        return extractIndex(text).isPresent()
                && contains(text, "播一下", "读一下", "念一下", "听一下", "听听", "播放", "朗读", "播报");
    }

    private boolean isNewsOnlyOperation(String text) {
        return contains(text,
                "刷新城市新闻", "刷新城市资讯", "刷新新闻", "刷新资讯",
                "不喜欢", "不要这条", "不想看", "不感兴趣", "喜欢第", "点赞",
                "跳过", "略过", "切换城市", "选择城市", "换个城市", "换个地方",
                "播报新闻", "朗读新闻", "听新闻", "听听新闻", "听一下新闻", "播放新闻",
                "播报资讯", "朗读资讯", "听资讯", "听听资讯", "听一下资讯", "播放资讯");
    }

    private boolean isHomePage(String page) {
        return page.isBlank() || "home".equalsIgnoreCase(page);
    }

    private boolean isVisionPage(String page) {
        return "vision".equalsIgnoreCase(page);
    }

    private Optional<String> extractVisionScene(String text) {
        if (!contains(text, "识别", "识图", "模式", "场景", "切换", "想看", "想进行", "进行",
                "想读", "读读", "读书", "看书", "念书", "读文字", "读文本", "读一下")) {
            return Optional.empty();
        }
        return normalizeVisionScene(text);
    }

    private Optional<String> normalizeVisionScene(String text) {
        String value = cleanText(text);
        if (value.isBlank()) {
            return Optional.empty();
        }
        if (contains(value, "crossroad", "十字路口", "十足路口", "路口", "斑马线", "红绿灯")) {
            return Optional.of("crossroad");
        }
        if (contains(value, "supermarket", "超市", "货架", "商店", "购物")) {
            return Optional.of("supermarket");
        }
        if (contains(value, "text-reading", "text", "文本", "文字", "读字", "识字", "阅读",
                "读书", "读读书", "看书", "念书", "书本", "书页", "课本", "小说", "菜单", "说明书",
                "帮我读", "帮我念", "读文字", "读文本", "读一下书")) {
            return Optional.of("text-reading");
        }
        if (contains(value, "general", "通用", "普通", "默认", "常规")) {
            return Optional.of("general");
        }
        return Optional.empty();
    }

    private String visionSceneLabel(String scene) {
        return switch (scene) {
            case "crossroad" -> "十字路口";
            case "supermarket" -> "超市";
            case "text-reading" -> "文本";
            default -> "通用";
        };
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
