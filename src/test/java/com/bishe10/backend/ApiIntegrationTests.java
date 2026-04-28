package com.bishe10.backend;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.service.NewsSourceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiIntegrationTests {

    private static final Path STORAGE_DIR = createStorageDir();

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("bishe10.storage.root-dir", () -> STORAGE_DIR.toString());
        registry.add("bishe10.llm.enabled", () -> false);
        registry.add("bishe10.tts.enabled", () -> false);
        registry.add("bishe10.weather.enabled", () -> false);
    }

    @Test
    void healthEndpointExposesRuntimeFlags() {
        Map<String, Object> response = get("/api/health");
        Map<String, Object> data = data(response);

        assertThat(response.get("success")).isEqualTo(true);
        assertThat(data.get("mode")).isEqualTo("mvp-live");
        assertThat(data.get("llmConfigured")).isEqualTo(false);
        assertThat(data.get("ttsConfigured")).isEqualTo(false);
    }

    @Test
    void newsEndpointReturnsDigestPayload() {
        Map<String, Object> response = client()
                .get()
                .uri(uriBuilder -> uriBuilder.path("/api/news/recommendations").queryParam("city", "上海").build())
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        Map<String, Object> headline = castMap(data.get("headline"));
        List<Map<String, Object>> newsList = castList(data.get("newsList"));
        Map<String, Object> locationContext = castMap(data.get("locationContext"));
        List<Map<String, Object>> quickEntries = castList(data.get("quickEntries"));
        Map<String, Object> weather = castMap(data.get("weather"));

        assertThat(headline.get("title")).isEqualTo("上海 无障碍资讯速览");
        assertThat(data.get("feedMode")).isEqualTo("city-contextual-digest");
        assertThat(locationContext.get("city")).isEqualTo("上海");
        assertThat(locationContext.get("source")).isEqualTo("manual");
        assertThat(quickEntries).hasSize(2);
        assertThat(quickEntries.get(0).get("label")).isEqualTo("出行");
        assertThat(quickEntries.get(1).get("label")).isEqualTo("快讯");
        assertThat(weather.get("location")).isEqualTo("上海");
        assertThat(weather.get("travel_advice")).isNotNull();
        assertThat(newsList).hasSizeGreaterThanOrEqualTo(6);
        assertThat(newsList.get(0).get("spokenText")).isNotNull();
    }

    @Test
    void newsEndpointResolvesCityFromCoordinates() {
        Map<String, Object> response = client()
                .get()
                .uri(uriBuilder -> uriBuilder.path("/api/news/recommendations")
                        .queryParam("lat", 31.2304)
                        .queryParam("lng", 121.4737)
                        .build())
                .retrieve()
                .body(Map.class);

        Map<String, Object> locationContext = castMap(data(response).get("locationContext"));
        assertThat(locationContext.get("city")).isEqualTo("上海");
        assertThat(locationContext.get("source")).isEqualTo("gps");
        assertThat(locationContext.get("permission")).isEqualTo("granted");
    }

    @Test
    void newsEndpointFallsBackToDefaultCity() {
        Map<String, Object> response = get("/api/news/recommendations");
        Map<String, Object> data = data(response);
        Map<String, Object> locationContext = castMap(data.get("locationContext"));
        List<Map<String, Object>> newsList = castList(data.get("newsList"));

        assertThat(locationContext.get("city")).isEqualTo("上海");
        assertThat(locationContext.get("source")).isEqualTo("default");
        assertThat(newsList).hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    void weatherEndpointReturnsStructuredPayload() {
        Map<String, Object> response = client()
                .get()
                .uri(uriBuilder -> uriBuilder.path("/api/weather/local").queryParam("city", "上海").build())
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        assertThat(data.get("location")).isEqualTo("上海");
        assertThat(data.get("available")).isEqualTo(false);
        assertThat(data.get("weather_text")).isEqualTo("天气待确认");
        assertThat(data.get("travel_advice")).isNotNull();
        assertThat(data.get("spoken_text")).isNotNull();
    }

    @Test
    void newsFilteringRejectsPortalAndFinanceTickerPages() throws Exception {
        NewsSourceClient client = createNewsSourceClient();

        assertThat(invokeLooksLikeNewsArticle(
                client,
                "上海",
                "上海热点报道_城事资讯_新浪上海",
                "上海城事是新浪上海的新闻中心，实时报道热点新闻。",
                "新浪网",
                "https://news.sina.com.cn/shanghai"
        )).isFalse();

        assertThat(invokeLooksLikeNewsArticle(
                client,
                "上海",
                "上海板块资金流向明细",
                "东方财富网提供沪深两市各板块的资金流向，及时了解各行业板块资金净流入情况。",
                "东方财富网",
                "https://finance.eastmoney.com/a/202604021234.html"
        )).isFalse();
    }

    @Test
    void newsFilteringKeepsMeaningfulDevelopmentArticle() throws Exception {
        NewsSourceClient client = createNewsSourceClient();

        assertThat(invokeLooksLikeNewsArticle(
                client,
                "上海",
                "多领域发展成果提振信心 中国经济“拔节向上”底气足",
                "2024年中国品牌日活动在上海启动，主题是“中国品牌，世界共享”，近1800家企业参展。",
                "央视网",
                "https://news.cctv.com/2024/05/10/ARTIexample.shtml"
        )).isTrue();
    }

    @Test
    void visionJsonEndpointFallsBackWithoutImage() {
        Map<String, Object> response = client()
                .post()
                .uri("/api/vision/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("scene", "crossroad"))
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        assertThat(data.get("scene")).isEqualTo("crossroad");
        assertThat(data.get("analysisMode")).isEqualTo("template-fallback");
        assertThat(data.get("voiceBroadcast")).isNotNull();
    }

    @Test
    void visionTextReadingEndpointReturnsReadingPayloadWithoutImage() {
        Map<String, Object> response = client()
                .post()
                .uri("/api/vision/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("scene", "text-reading"))
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        assertThat(data.get("scene")).isEqualTo("text-reading");
        assertThat(data.get("sceneTitle")).isEqualTo("文本阅读");
        assertThat(data.get("analysisMode")).isEqualTo("template-fallback");
        assertThat(data.get("recognizedText")).isNotNull();
        assertThat(data.get("readingText")).isNotNull();
        assertThat(data.get("voiceBroadcast")).isNotNull();
        assertThat(data.get("textLength")).isInstanceOfAny(Number.class, Integer.class);
    }

    @Test
    void visionSamplesEndpointReturnsBuiltInDemoImages() {
        Map<String, Object> response = get("/api/vision/samples");
        List<Map<String, Object>> items = castList(data(response).get("items"));

        assertThat(items).hasSize(2);
        assertThat(items.get(0).get("key")).isEqualTo("crosswalk-demo");
        assertThat(items.get(0).get("imageUrl")).isEqualTo("/api/vision/samples/crosswalk-demo/image");
    }

    @Test
    void visionJsonEndpointAcceptsSampleKey() {
        Map<String, Object> response = client()
                .post()
                .uri("/api/vision/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "scene", "crossroad",
                        "sampleKey", "crosswalk-demo"
                ))
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        assertThat(data.get("scene")).isEqualTo("crossroad");
        assertThat(data.get("sampleKey")).isEqualTo("crosswalk-demo");
        assertThat(data.get("sampleImageUrl")).isEqualTo("/api/vision/samples/crosswalk-demo/image");
    }

    @Test
    void visionStreamEndpointReturnsSsePayload() {
        String body = client()
                .post()
                .uri("/api/vision/analyze/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("scene", "crossroad"))
                .retrieve()
                .body(String.class);

        assertThat(body).contains("event:status");
        assertThat(body).contains("event:preview");
        assertThat(body).contains("event:result");
    }

    @Test
    void visionMultipartEndpointAcceptsUploadAndPersistsHistory() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("scene", "supermarket");
        form.add("image", new ByteArrayResource(new byte[]{1, 2, 3, 4}) {
            @Override
            public String getFilename() {
                return "tiny.png";
            }
        });

        Map<String, Object> response = client()
                .post()
                .uri("/api/vision/analyze")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        assertThat(data.get("scene")).isEqualTo("supermarket");
        assertThat(data.get("sceneTitle")).isEqualTo("超市货架识别");

        Map<String, Object> history = get("/api/history");
        List<Map<String, Object>> items = castList(data(history).get("items"));
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).get("type")).isEqualTo("vision");
    }

    @Test
    void voiceEndpointReturnsUnavailableWhenTtsDisabled() {
        Map<String, Object> response = client()
                .post()
                .uri("/api/voice/synthesize")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "text", "前方有路口，请先确认红绿灯。",
                        "title", "播报测试",
                        "source", "test"
                ))
                .retrieve()
                .body(Map.class);

        Map<String, Object> data = data(response);
        assertThat(data.get("available")).isEqualTo(false);
        assertThat(data.get("provider")).isEqualTo("Edge TTS");
    }

    private Map<String, Object> get(String path) {
        return client()
                .get()
                .uri(path)
                .retrieve()
                .body(Map.class);
    }

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .build();
    }

    private NewsSourceClient createNewsSourceClient() {
        Bishe10Properties properties = new Bishe10Properties();
        properties.getNews().setEnabled(true);
        properties.getNews().setBaseUrl("https://www.baidu.com/s");
        return new NewsSourceClient(properties, objectMapper);
    }

    private boolean invokeLooksLikeNewsArticle(
            NewsSourceClient client,
            String city,
            String title,
            String summary,
            String source,
            String url
    ) throws Exception {
        Method method = NewsSourceClient.class.getDeclaredMethod(
                "looksLikeNewsArticle",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class
        );
        method.setAccessible(true);
        return (boolean) method.invoke(client, city, title, summary, source, url);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(Map<String, Object> response) {
        return castMap(response.get("data"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return objectMapper.convertValue(value, Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castList(Object value) {
        return objectMapper.convertValue(value, List.class);
    }

    private static Path createStorageDir() {
        try {
            return Files.createTempDirectory("bishe10-api-tests-");
        } catch (IOException error) {
            throw new IllegalStateException("Failed to create temp storage dir", error);
        }
    }
}
