package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.model.NewsSourceConfig;
import com.bishe10.backend.repository.NewsSourceConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class NewsSourceClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewsSourceClient.class);

    private static final ZoneOffset CHINA_OFFSET = ZoneOffset.ofHours(8);
    private static final long SEARCH_BLOCK_COOLDOWN_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long FETCH_TIME_BUDGET_MILLIS = Duration.ofSeconds(10).toMillis();
    private static final int MAX_SEARCH_QUERIES_PER_SOURCE = 2;
    private static final int MAX_SEARCH_SOURCES_PER_REQUEST = 1;
    private static final DateTimeFormatter BAIDU_DATE_TIME = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final DateTimeFormatter BAIDU_DATE = DateTimeFormatter.ofPattern("yyyy年M月d日");
    private static final DateTimeFormatter ISO_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]");

    private static final Pattern BAIDU_S_DATA_PATTERN = Pattern.compile("<!--s-data:(\\{.*?})-->", Pattern.DOTALL);
    private static final Pattern SOGOU_RESULT_PATTERN = Pattern.compile(
            "<div class=\"vrwrap\" id=\"sogou_vr_[^\"]+_wrap_\\d+\">.*?"
                    + "<a id=\"sogou_vr_[^\"]+\"[^>]*href=\"(?<url>[^\"]+)\"[^>]*>(?<title>.*?)</a>.*?"
                    + "<p class=\"news-from[^\"]*\">\\s*<span>(?<source>.*?)</span>\\s*<span>(?<time>.*?)</span>\\s*</p>.*?"
                    + "<p class=\"star-wiki\"[^>]*>(?<summary>.*?)</p>",
            Pattern.DOTALL
    );
    private static final Pattern SUMMARY_DATE_PATTERN = Pattern.compile("(\\d{4}年\\d{1,2}月\\d{1,2}日(?:\\s+\\d{1,2}:\\d{2})?)");
    private static final List<String> REGION_TOKENS = List.of(
            "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
            "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
            "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
            "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆"
    );

    private final Bishe10Properties.News properties;
    private final ObjectMapper objectMapper;
    private final NewsSourceConfigRepository sourceRepository;
    private final HttpClient httpClient;
    private volatile long baiduBlockedUntilMillis;
    private volatile long sogouBlockedUntilMillis;

    public NewsSourceClient(Bishe10Properties bishe10Properties, ObjectMapper objectMapper) {
        this(bishe10Properties, objectMapper, new NewsSourceConfigRepository(bishe10Properties));
    }

    @Autowired
    public NewsSourceClient(
            Bishe10Properties bishe10Properties,
            ObjectMapper objectMapper,
            NewsSourceConfigRepository sourceRepository
    ) {
        this.properties = bishe10Properties.getNews();
        this.objectMapper = objectMapper;
        this.sourceRepository = sourceRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(newsRequestTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<NewsArticle> fetchByCity(String city) {
        if (!isConfigured()) {
            return List.of();
        }

        String normalizedCity = normalizeCityName(city);
        if (normalizedCity.isBlank()) {
            return List.of();
        }

        int limit = Math.max(8, properties.getMaxPerQuery());
        return fetchByQueries(normalizedCity, buildQueries(normalizedCity), limit, true);
    }

    public List<NewsArticle> fetchByQueries(String scopeToken, List<String> queries, int limit, boolean requireScopeMatch) {
        if (!properties.isEnabled() || queries == null || queries.isEmpty()) {
            return List.of();
        }

        String normalizedScope = normalizeCityName(scopeToken);
        int effectiveLimit = Math.max(1, limit);
        List<NewsArticle> items = new ArrayList<>();
        Set<String> dedupeKeys = new LinkedHashSet<>();
        List<NewsSourceConfig> sources = orderSourcesForFastResponse(enabledSources(), normalizedScope, requireScopeMatch);
        if (sources.isEmpty()) {
            return List.of();
        }

        long deadlineMillis = System.currentTimeMillis() + FETCH_TIME_BUDGET_MILLIS;
        int perSourceLimit = Math.max(4, Math.min(effectiveLimit, (int) Math.ceil(effectiveLimit / (double) sources.size()) + 4));
        int usedSearchSources = 0;
        for (NewsSourceConfig source : sources) {
            if (timeBudgetExpired(deadlineMillis)) {
                LOGGER.info("news fetch time budget exhausted scope={} items={}", normalizedScope, items.size());
                break;
            }

            String sourceType = normalizedSourceType(source);
            if (isSearchSource(sourceType)) {
                if (usedSearchSources >= MAX_SEARCH_SOURCES_PER_REQUEST && !items.isEmpty()) {
                    continue;
                }
                usedSearchSources++;
            }

            addAll(items, dedupeKeys, fetchFromSource(
                    source,
                    normalizedScope,
                    queriesForSource(queries, sourceType),
                    perSourceLimit,
                    requireScopeMatch,
                    deadlineMillis
            ));
            if (items.size() >= effectiveLimit) {
                break;
            }
        }

        return sortedLimited(items, effectiveLimit);
    }

    public boolean isConfigured() {
        return properties.isEnabled()
                && properties.getBaseUrl() != null
                && !properties.getBaseUrl().isBlank();
    }

    private boolean isBaiduInCooldown() {
        return System.currentTimeMillis() < baiduBlockedUntilMillis;
    }

    private boolean isSogouInCooldown() {
        return System.currentTimeMillis() < sogouBlockedUntilMillis;
    }

    private void markBaiduBlocked() {
        baiduBlockedUntilMillis = System.currentTimeMillis() + SEARCH_BLOCK_COOLDOWN_MILLIS;
    }

    private void markSogouBlocked() {
        sogouBlockedUntilMillis = System.currentTimeMillis() + SEARCH_BLOCK_COOLDOWN_MILLIS;
    }

    private void markSearchBlocked() {
        long blockedUntil = System.currentTimeMillis() + SEARCH_BLOCK_COOLDOWN_MILLIS;
        baiduBlockedUntilMillis = Math.max(baiduBlockedUntilMillis, blockedUntil);
        sogouBlockedUntilMillis = Math.max(sogouBlockedUntilMillis, blockedUntil);
    }

    private Duration newsRequestTimeout() {
        return Duration.ofSeconds(Math.max(2, Math.min(8, properties.getTimeoutSeconds())));
    }

    private boolean timeBudgetExpired(long deadlineMillis) {
        return System.currentTimeMillis() >= deadlineMillis - 250;
    }

    private String normalizedSourceType(NewsSourceConfig source) {
        return source.sourceType() == null ? "" : source.sourceType().trim().toUpperCase(Locale.ROOT);
    }

    private boolean isSearchSource(String sourceType) {
        return "BAIDU_SEARCH".equals(sourceType) || "SOGOU_SEARCH".equals(sourceType);
    }

    private List<String> queriesForSource(List<String> queries, String sourceType) {
        if (!isSearchSource(sourceType) || queries.size() <= MAX_SEARCH_QUERIES_PER_SOURCE) {
            return queries;
        }
        return queries.subList(0, MAX_SEARCH_QUERIES_PER_SOURCE);
    }

    private boolean shouldRequireSearchScopeMatch(String scopeToken, String query, boolean requireScopeMatch) {
        if (!requireScopeMatch) {
            return false;
        }
        String normalizedScope = normalizeCityName(scopeToken);
        if (normalizedScope.isBlank() || isGenericScope(normalizedScope)) {
            return false;
        }
        return query == null || !query.contains(normalizedScope);
    }

    private List<NewsSourceConfig> orderSourcesForFastResponse(List<NewsSourceConfig> sources, String scopeToken, boolean requireScopeMatch) {
        List<NewsSourceConfig> ordered = new ArrayList<>(sources);
        ordered.sort((left, right) -> {
            int rankCompare = Integer.compare(sourceSpeedRank(left, scopeToken, requireScopeMatch), sourceSpeedRank(right, scopeToken, requireScopeMatch));
            if (rankCompare != 0) {
                return rankCompare;
            }
            int priorityCompare = Integer.compare(left.priority(), right.priority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            return left.sourceKey().compareTo(right.sourceKey());
        });
        return ordered;
    }

    private int sourceSpeedRank(NewsSourceConfig source, String scopeToken, boolean requireScopeMatch) {
        String type = normalizedSourceType(source);
        if (requireScopeMatch && !isGenericScope(scopeToken)) {
            if ("SOGOU_SEARCH".equals(type)) {
                return 0;
            }
            if ("BAIDU_SEARCH".equals(type)) {
                return 1;
            }
            if ("RSS".equals(type)) {
                return 2;
            }
            return 3;
        }
        if ("RSS".equals(type)) {
            return 0;
        }
        if ("SOGOU_SEARCH".equals(type)) {
            return 1;
        }
        if ("BAIDU_SEARCH".equals(type)) {
            return 2;
        }
        return 3;
    }

    private List<NewsArticle> sortedLimited(List<NewsArticle> items, int limit) {
        List<NewsArticle> sorted = new ArrayList<>(items == null ? List.of() : items);
        sorted.sort((left, right) -> comparePublishedAtDesc(left.publishedAt(), right.publishedAt()));
        if (sorted.size() <= limit) {
            return sorted;
        }
        return new ArrayList<>(sorted.subList(0, limit));
    }

    private List<NewsSourceConfig> enabledSources() {
        try {
            sourceRepository.initializeDefaults();
            return sourceRepository.findEnabled();
        } catch (Exception error) {
            LOGGER.warn("load enabled news sources failed, using default enabled sources", error);
            return sourceRepository.defaultConfigs().stream()
                    .filter(NewsSourceConfig::enabled)
                    .toList();
        }
    }

    private List<String> buildQueries(String city) {
        List<String> queries = new ArrayList<>();
        queries.add(city + " 新闻");
        queries.add(city + " 民生 新闻");
        queries.add(city + " 交通 新闻");
        queries.add(city + " 社区 新闻");
        queries.add(city + " 公共服务 新闻");
        queries.add(city + " 发展 新闻");
        return queries;
    }

    private void addAll(List<NewsArticle> items, Set<String> dedupeKeys, List<NewsArticle> candidates) {
        for (NewsArticle article : candidates) {
            if (dedupeKeys.add(dedupeKey(article))) {
                items.add(article);
            }
        }
    }

    private List<NewsArticle> fetchFromSource(
            NewsSourceConfig source,
            String scopeToken,
            List<String> queries,
            int limit,
            boolean requireScopeMatch,
            long deadlineMillis
    ) {
        if (timeBudgetExpired(deadlineMillis)) {
            sourceRepository.recordFetch(source.sourceKey(), 0, "TIME_BUDGET", "Skipped to keep recommendation response fast.");
            return List.of();
        }
        String sourceType = normalizedSourceType(source);
        if ("RSS".equals(sourceType)) {
            List<NewsArticle> items = sortedLimited(fetchFromRssFeed(new RssFeed(source.sourceName(), source.endpoint()), scopeToken, requireScopeMatch), limit);
            String status = items.isEmpty() ? "EMPTY" : "OK";
            sourceRepository.recordFetch(source.sourceKey(), items.size(), status, "本次采集 " + items.size() + " 条候选新闻");
            return items;
        }

        if ("BAIDU_SEARCH".equals(sourceType)) {
            return fetchFromBaiduSource(source, scopeToken, queries, limit, requireScopeMatch, deadlineMillis);
        }
        if ("SOGOU_SEARCH".equals(sourceType)) {
            return fetchFromSogouSource(source, scopeToken, queries, limit, requireScopeMatch, deadlineMillis);
        }

        LOGGER.info("Unsupported news source type={} key={}", source.sourceType(), source.sourceKey());
        sourceRepository.recordFetch(source.sourceKey(), 0, "SKIPPED", "不支持的新闻源类型：" + source.sourceType());
        return List.of();
    }

    private List<NewsArticle> fetchFromBaiduSource(
            NewsSourceConfig source,
            String scopeToken,
            List<String> queries,
            int limit,
            boolean requireScopeMatch,
            long deadlineMillis
    ) {
        if (isBaiduInCooldown()) {
            sourceRepository.recordFetch(source.sourceKey(), 0, "COOLDOWN", "百度搜索近期被拦截，暂时跳过");
            return List.of();
        }

        int effectiveLimit = Math.max(1, limit);
        List<NewsArticle> items = new ArrayList<>();
        Set<String> dedupeKeys = new LinkedHashSet<>();
        boolean blocked = false;
        for (String query : queries) {
            if (timeBudgetExpired(deadlineMillis)) {
                break;
            }
            FetchResult result = fetchFromBaidu(scopeToken, query, 0, shouldRequireSearchScopeMatch(scopeToken, query, requireScopeMatch));
            addAll(items, dedupeKeys, result.items());
            if (result.blocked()) {
                markBaiduBlocked();
                blocked = true;
                break;
            }
            if (items.size() >= effectiveLimit) {
                break;
            }
        }
        List<NewsArticle> result = sortedLimited(items, effectiveLimit);
        sourceRepository.recordFetch(
                source.sourceKey(),
                result.size(),
                blocked ? "BLOCKED" : result.isEmpty() ? "EMPTY" : "OK",
                blocked ? "百度新闻搜索被安全验证拦截" : "本次采集 " + result.size() + " 条候选新闻"
        );
        return result;
    }

    private List<NewsArticle> fetchFromSogouSource(
            NewsSourceConfig source,
            String scopeToken,
            List<String> queries,
            int limit,
            boolean requireScopeMatch,
            long deadlineMillis
    ) {
        if (isSogouInCooldown()) {
            sourceRepository.recordFetch(source.sourceKey(), 0, "COOLDOWN", "搜狗搜索近期跳转或超时，暂时跳过");
            return List.of();
        }

        int effectiveLimit = Math.max(1, limit);
        List<NewsArticle> items = new ArrayList<>();
        Set<String> dedupeKeys = new LinkedHashSet<>();
        boolean blocked = false;
        for (String query : queries) {
            if (timeBudgetExpired(deadlineMillis)) {
                break;
            }
            boolean queryScoped = shouldRequireSearchScopeMatch(scopeToken, query, requireScopeMatch);
            SearchFetchResult firstPage = fetchFromSogou(scopeToken, query, 1, queryScoped);
            addAll(items, dedupeKeys, firstPage.items());
            if (firstPage.unavailable()) {
                markSogouBlocked();
                blocked = true;
                break;
            }

            if (items.size() < effectiveLimit && query.equals(queries.get(0)) && !timeBudgetExpired(deadlineMillis)) {
                SearchFetchResult secondPage = fetchFromSogou(scopeToken, query, 2, queryScoped);
                addAll(items, dedupeKeys, secondPage.items());
                if (secondPage.unavailable()) {
                    markSogouBlocked();
                    blocked = true;
                    break;
                }
            }

            if (items.size() >= effectiveLimit) {
                break;
            }
        }
        List<NewsArticle> result = sortedLimited(items, effectiveLimit);
        sourceRepository.recordFetch(
                source.sourceKey(),
                result.size(),
                blocked ? "BLOCKED" : result.isEmpty() ? "EMPTY" : "OK",
                blocked ? "搜狗新闻搜索跳转、超时或不可用" : "本次采集 " + result.size() + " 条候选新闻"
        );
        return result;
    }

    private FetchResult fetchFromBaidu(String city, String query, int offset) {
        return fetchFromBaidu(city, query, offset, true);
    }

    private FetchResult fetchFromBaidu(String city, String query, int offset, boolean requireScopeMatch) {
        try {
            List<HttpCookie> cookies = warmUpCookies();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(buildBaiduSearchUrl(query, offset)))
                    .timeout(newsRequestTimeout())
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
                    .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("accept-language", "zh-CN,zh;q=0.9")
                    .header("cache-control", "no-cache")
                    .header("pragma", "no-cache")
                    .header("referer", "https://www.baidu.com/")
                    .header("upgrade-insecure-requests", "1")
                    .header("cookie", joinCookies(cookies))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("Baidu news request failed with status {} for query {}", response.statusCode(), query);
                return FetchResult.empty();
            }

            String body = response.body();
            if (isBaiduBlockedPage(body)) {
                LOGGER.warn("Baidu news search page was blocked for query {}", query);
                return new FetchResult(List.of(), true);
            }
            return new FetchResult(parseBaiduArticles(city, body, requireScopeMatch), false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Baidu news request interrupted", error);
            return FetchResult.empty();
        } catch (IOException error) {
            LOGGER.warn("Baidu news request returned invalid payload", error);
            return FetchResult.empty();
        } catch (Exception error) {
            LOGGER.warn("Baidu news request failed", error);
            return FetchResult.empty();
        }
    }

    private SearchFetchResult fetchFromSogou(String city, String query, int page) {
        return fetchFromSogou(city, query, page, true);
    }

    private SearchFetchResult fetchFromSogou(String city, String query, int page, boolean requireScopeMatch) {
        try {
            HttpResponse<String> response = sendNewsPage(buildSogouSearchUrl(query, page), "https://news.sogou.com/");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (isRedirect(response.statusCode())) {
                    LOGGER.info("Sogou news search redirected with status {} for query {}, switching source", response.statusCode(), query);
                } else {
                    LOGGER.warn("Sogou news request failed with status {} for query {}", response.statusCode(), query);
                }
                return new SearchFetchResult(List.of(), true);
            }

            List<NewsArticle> items = parseSogouArticles(city, response.body(), requireScopeMatch);
            if (!items.isEmpty()) {
                LOGGER.info("Sogou news fallback returned {} items for query {}", items.size(), query);
            }
            return new SearchFetchResult(items, false);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Sogou news request interrupted", error);
            return new SearchFetchResult(List.of(), true);
        } catch (IOException error) {
            LOGGER.warn("Sogou news request returned invalid payload", error);
            return new SearchFetchResult(List.of(), true);
        } catch (Exception error) {
            LOGGER.warn("Sogou news request failed", error);
            return new SearchFetchResult(List.of(), true);
        }
    }

    private List<NewsArticle> fetchFromRssFeed(RssFeed feed, String scopeToken, boolean requireScopeMatch) {
        try {
            if (feed.url() == null || feed.url().isBlank()) {
                return List.of();
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feed.url()))
                    .timeout(newsRequestTimeout())
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
                    .header("accept", "application/rss+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
                    .header("accept-language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.info("RSS news source returned status {} url={}", response.statusCode(), feed.url());
                return List.of();
            }
            List<NewsArticle> items = parseRssArticles(feed, scopeToken, response.body(), requireScopeMatch);
            if (!items.isEmpty()) {
                LOGGER.info("RSS news source returned {} items source={} scope={}", items.size(), feed.source(), scopeToken);
            }
            return items;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.warn("RSS news request interrupted source={}", feed.source(), error);
            return List.of();
        } catch (Exception error) {
            LOGGER.warn("RSS news request failed source={} url={}", feed.source(), feed.url(), error);
            return List.of();
        }
    }

    private HttpResponse<String> sendNewsPage(String url, String referer) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(newsRequestTimeout())
                .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
                .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("accept-language", "zh-CN,zh;q=0.9")
                .header("referer", referer)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isRedirect(response.statusCode())) {
            return response;
        }

        String location = response.headers().firstValue("location").orElse("");
        if (location.isBlank()) {
            return response;
        }

        URI target = request.uri().resolve(location);
        HttpRequest redirected = HttpRequest.newBuilder()
                .uri(target)
                .timeout(newsRequestTimeout())
                .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
                .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                .header("accept-language", "zh-CN,zh;q=0.9")
                .header("referer", referer)
                .GET()
                .build();
        return httpClient.send(redirected, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private List<HttpCookie> warmUpCookies() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.baidu.com/"))
                    .timeout(newsRequestTimeout())
                    .header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36")
                    .header("accept-language", "zh-CN,zh;q=0.9")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return extractCookies(response.headers());
        } catch (Exception error) {
            return List.of();
        }
    }

    private List<HttpCookie> extractCookies(HttpHeaders headers) {
        List<HttpCookie> cookies = new ArrayList<>();
        for (String value : headers.allValues("set-cookie")) {
            cookies.addAll(HttpCookie.parse(value));
        }
        return cookies;
    }

    private String joinCookies(List<HttpCookie> cookies) {
        if (cookies.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (HttpCookie cookie : cookies) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            builder.append(cookie.getName()).append("=").append(cookie.getValue());
        }
        return builder.toString();
    }

    private String buildBaiduSearchUrl(String query, int offset) {
        return "%s?tn=news&rtt=4&bsst=1&cl=2&rn=%s&pn=%s&word=%s".formatted(
                normalizeBaseUrl(properties.getBaseUrl()),
                Math.max(10, properties.getMaxPerQuery()),
                Math.max(0, offset),
                URLEncoder.encode(query, StandardCharsets.UTF_8)
        );
    }

    private String buildSogouSearchUrl(String query, int page) {
        return "https://news.sogou.com/news?query=%s&page=%d&ie=utf8".formatted(
                URLEncoder.encode(query, StandardCharsets.UTF_8),
                Math.max(1, page)
        );
    }

    private boolean isBaiduBlockedPage(String body) {
        return body == null
                || body.isBlank()
                || body.contains("百度安全验证")
                || body.contains("网络不给力，请稍后重试");
    }

    private List<NewsArticle> parseBaiduArticles(String city, String html) {
        return parseBaiduArticles(city, html, true);
    }

    private List<NewsArticle> parseBaiduArticles(String city, String html, boolean requireScopeMatch) {
        List<NewsArticle> items = new ArrayList<>();
        Matcher matcher = BAIDU_S_DATA_PATTERN.matcher(html);
        while (matcher.find()) {
            String rawJson = matcher.group(1);
            try {
                JsonNode root = objectMapper.readTree(rawJson);
                String title = cleanHtml(root.path("title").asText(""));
                String url = decodeUrl(root.path("titleUrl").asText(""));
                String summary = cleanHtml(root.path("summary").asText(""));
                String source = cleanHtml(root.path("sourceName").asText(""));
                String dispTime = cleanHtml(root.path("dispTime").asText(""));

                if (title.isBlank() || url.isBlank() || source.isBlank()) {
                    continue;
                }
                if (!looksLikeNewsArticle(city, title, summary, source, url, requireScopeMatch)) {
                    continue;
                }

                OffsetDateTime publishedAt = parseTime(dispTime, summary);
                items.add(new NewsArticle(
                        trimText(title, 120),
                        trimText(summary, 220),
                        trimText(summary, 360),
                        source,
                        url,
                        publishedAt,
                        classifyBucket(title, summary, summary),
                        city
                ));
            } catch (Exception ignore) {
                // Skip malformed payloads.
            }
        }
        return items;
    }

    private List<NewsArticle> parseSogouArticles(String city, String html) {
        return parseSogouArticles(city, html, true);
    }

    private List<NewsArticle> parseSogouArticles(String city, String html, boolean requireScopeMatch) {
        List<NewsArticle> items = new ArrayList<>();
        Matcher matcher = SOGOU_RESULT_PATTERN.matcher(html);
        while (matcher.find()) {
            String title = cleanHtml(matcher.group("title"));
            String url = decodeUrl(matcher.group("url"));
            String source = cleanHtml(matcher.group("source"));
            String time = cleanHtml(matcher.group("time"));
            String summary = cleanHtml(matcher.group("summary"));

            if (title.isBlank() || url.isBlank() || source.isBlank()) {
                continue;
            }
            if (!looksLikeNewsArticle(city, title, summary, source, url, requireScopeMatch)) {
                continue;
            }

            OffsetDateTime publishedAt = parseTime(time, summary);
            items.add(new NewsArticle(
                    trimText(title, 120),
                    trimText(summary, 220),
                    trimText(summary, 360),
                    source,
                    url,
                    publishedAt,
                    classifyBucket(title, summary, summary),
                    city
            ));
        }
        return items;
    }

    private List<NewsArticle> parseRssArticles(RssFeed feed, String scopeToken, String xml, boolean requireScopeMatch) {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }

        List<NewsArticle> items = new ArrayList<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(false);

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagName("item");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element item)) {
                    continue;
                }
                String title = cleanHtml(childText(item, "title"));
                String url = decodeUrl(childText(item, "link"));
                String summary = cleanHtml(childText(item, "description"));
                String source = feed.source();

                if (title.isBlank() || url.isBlank()) {
                    continue;
                }
                if (!looksLikeRssArticle(scopeToken, title, summary, source, url, requireScopeMatch)) {
                    continue;
                }

                OffsetDateTime publishedAt = parseRssTime(childText(item, "pubDate"));
                items.add(new NewsArticle(
                        trimText(title, 120),
                        trimText(summary, 220),
                        trimText(summary, 360),
                        source,
                        url,
                        publishedAt,
                        classifyBucket(title, summary, summary),
                        scopeToken
                ));
            }
        } catch (Exception error) {
            LOGGER.debug("RSS payload parse failed source={}", feed.source(), error);
            return List.of();
        }
        return items;
    }

    private boolean looksLikeRssArticle(String scopeToken, String title, String summary, String source, String url, boolean requireScopeMatch) {
        String normalizedScope = normalizeCityName(scopeToken);
        if (requireScopeMatch) {
            return looksLikeNewsArticle(normalizedScope, title, summary, source, url, true);
        }

        if (!isGenericScope(normalizedScope)) {
            String haystack = (title + " " + summary + " " + source + " " + url).toLowerCase(Locale.ROOT);
            if (!haystack.contains(normalizedScope.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return looksLikeNewsArticle("", title, summary, source, url, false);
    }

    private boolean isGenericScope(String scopeToken) {
        return scopeToken == null
                || scopeToken.isBlank()
                || "全国".equals(scopeToken)
                || "国内".equals(scopeToken)
                || "新闻".equals(scopeToken);
    }

    private String childText(Element item, String tagName) {
        NodeList nodes = item.getElementsByTagName(tagName);
        if (nodes.getLength() == 0 || nodes.item(0) == null) {
            return "";
        }
        return nodes.item(0).getTextContent() == null ? "" : nodes.item(0).getTextContent().trim();
    }

    private boolean looksLikeNewsArticle(String city, String title, String summary, String source, String url) {
        return looksLikeNewsArticle(city, title, summary, source, url, true);
    }

    private boolean looksLikeNewsArticle(String city, String title, String summary, String source, String url, boolean requireScopeMatch) {
        String haystack = (title + " " + summary + " " + source).toLowerCase(Locale.ROOT);
        String urlLower = url.toLowerCase(Locale.ROOT);
        String cityLower = city.toLowerCase(Locale.ROOT);
        String normalizedTitle = normalizeText(title);

        if (requireScopeMatch && !cityLower.isBlank() && !haystack.contains(cityLower) && !urlLower.contains(cityLower)) {
            return false;
        }

        if (normalizedTitle.length() < 4 || normalizedTitle.equals(cityLower)) {
            return false;
        }

        if (containsAny(haystack,
                "售楼处", "楼盘", "买房", "房价", "官方首页", "营销中心", "预约看房", "户型", "装修", "新房", "二手房")) {
            return false;
        }

        if (containsAny(haystack,
                "概况", "简介", "百科", "地方频道", "专题页", "推荐您搜索")) {
            return false;
        }

        if (containsAny(urlLower,
                "focus.cn", "fang.com", "house.", "leju", "data.house", "anjuke", "soufun")) {
            return false;
        }

        if (looksLikePortalLandingPage(title, summary, source)) {
            return false;
        }

        if (looksLikeWeatherSearchResult(title, summary, source, urlLower)) {
            return false;
        }

        if (looksLikeFinanceTickerPage(title, summary, source, urlLower)) {
            return false;
        }

        if (looksLikeRegionIndexPage(title, summary)) {
            return false;
        }

        return true;
    }

    private boolean looksLikePortalLandingPage(String title, String summary, String source) {
        String haystack = (title + " " + summary + " " + source).toLowerCase(Locale.ROOT);
        if (title != null && (title.contains("_") || title.contains("|"))) {
            return true;
        }
        return containsAny(haystack,
                "热点报道", "城事资讯", "新闻中心", "地方频道", "城市频道", "滚动新闻",
                "资讯首页", "频道首页", "首页 >", "首页_", "频道_", "专题合集", "新闻聚合");
    }

    private boolean looksLikeWeatherSearchResult(String title, String summary, String source, String urlLower) {
        String haystack = (title + " " + summary + " " + source).toLowerCase(Locale.ROOT);
        if (containsAny(urlLower, "weather", "tianqi", "qweather", "weather.com.cn", "forecast")) {
            return true;
        }

        if (containsAny(haystack,
                "天气", "天气预报", "今日天气", "实时天气", "24小时天气", "7天天气", "15天天气",
                "空气质量", "降雨量", "降水量", "穿衣指数", "紫外线", "天气雷达", "台风路径")) {
            return true;
        }

        return title != null && title.matches(".*(天气|天气预报|24小时|7天|15天|空气质量).*");
    }

    private boolean looksLikeFinanceTickerPage(String title, String summary, String source, String urlLower) {
        String haystack = (title + " " + summary + " " + source).toLowerCase(Locale.ROOT);
        if (containsAny(urlLower, "eastmoney", "10jqka", "stock", "finance.sina", "jrj.com", "cnstock")) {
            return true;
        }
        return containsAny(haystack,
                "板块资金流向", "资金流向", "主力资金", "沪深两市", "个股", "板块", "概念板块",
                "行情", "收盘", "开盘", "涨停", "跌停", "证券", "基金", "股票", "股市", "研报", "盘口");
    }

    private boolean looksLikeRegionIndexPage(String title, String summary) {
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle.length() > 6) {
            return false;
        }

        int hitCount = 0;
        String haystack = summary == null ? "" : summary;
        for (String token : REGION_TOKENS) {
            if (haystack.contains(token)) {
                hitCount++;
                if (hitCount >= 6) {
                    return true;
                }
            }
        }
        return false;
    }

    private String cleanHtml(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("<!--.*?-->", "")
                .replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&#39;", "'")
                .replace("&#34;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String decodeUrl(String url) {
        return url == null ? "" : url.replace("&amp;", "&").trim();
    }

    private int comparePublishedAtDesc(OffsetDateTime left, OffsetDateTime right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return 1;
        }
        if (right == null) {
            return -1;
        }
        return right.compareTo(left);
    }

    private String classifyBucket(String title, String description, String content) {
        String text = (title + " " + description + " " + content).toLowerCase(Locale.ROOT);
        if (containsAny(text, "地铁", "公交", "高铁", "火车", "站点", "封路", "绕行", "施工", "电梯", "无障碍", "盲道", "出行", "交通")) {
            return "travel";
        }
        if (containsAny(text, "医院", "门诊", "社区", "志愿", "便民", "养老", "助残", "服务", "义诊", "社工", "活动")) {
            return "service";
        }
        if (containsAny(text, "天气", "暴雨", "台风", "高温", "寒潮", "空气", "停电", "预警", "积水", "大风", "雷电")) {
            return "environment";
        }
        return "service";
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private OffsetDateTime parseTime(String text, String summary) {
        OffsetDateTime parsed = parseDisplayTime(text);
        if (parsed != null) {
            return parsed;
        }

        Matcher matcher = SUMMARY_DATE_PATTERN.matcher(summary == null ? "" : summary);
        if (matcher.find()) {
            return parseDisplayTime(matcher.group(1));
        }
        return null;
    }

    private OffsetDateTime parseRssTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toOffsetDateTime();
        } catch (DateTimeParseException ignored) {
            // Some feeds use local date formats instead of RFC-1123.
        }
        return parseDisplayTime(text);
    }

    private OffsetDateTime parseDisplayTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String text = raw.trim();
        try {
            if (text.contains("分钟前")) {
                return OffsetDateTime.now(CHINA_OFFSET).minusMinutes(extractLong(text));
            }
            if (text.contains("小时前")) {
                return OffsetDateTime.now(CHINA_OFFSET).minusHours(extractLong(text));
            }
            if (text.contains("天前")) {
                return OffsetDateTime.now(CHINA_OFFSET).minusDays(extractLong(text));
            }
            if (text.startsWith("昨天")) {
                String timePart = text.replace("昨天", "").trim();
                LocalDate date = LocalDate.now(CHINA_OFFSET).minusDays(1);
                if (timePart.contains(":")) {
                    return LocalDateTime.parse(date + " " + timePart, ISO_DATE_TIME).atOffset(CHINA_OFFSET);
                }
                return date.atStartOfDay().atOffset(CHINA_OFFSET);
            }
            if (text.startsWith("前天")) {
                String timePart = text.replace("前天", "").trim();
                LocalDate date = LocalDate.now(CHINA_OFFSET).minusDays(2);
                if (timePart.contains(":")) {
                    return LocalDateTime.parse(date + " " + timePart, ISO_DATE_TIME).atOffset(CHINA_OFFSET);
                }
                return date.atStartOfDay().atOffset(CHINA_OFFSET);
            }
            if (text.matches("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}(:\\d{2})?")) {
                return LocalDateTime.parse(text, ISO_DATE_TIME).atOffset(CHINA_OFFSET);
            }
            if (text.matches("\\d{4}年\\d{1,2}月\\d{1,2}日\\s+\\d{1,2}:\\d{2}")) {
                return LocalDateTime.parse(text, BAIDU_DATE_TIME).atOffset(CHINA_OFFSET);
            }
            if (text.matches("\\d{4}年\\d{1,2}月\\d{1,2}日")) {
                return LocalDate.parse(text, BAIDU_DATE).atStartOfDay().atOffset(CHINA_OFFSET);
            }
            if (text.matches("\\d{1,2}:\\d{2}")) {
                return LocalDateTime.parse(LocalDate.now(CHINA_OFFSET) + " " + text, ISO_DATE_TIME).atOffset(CHINA_OFFSET);
            }
        } catch (DateTimeParseException error) {
            LOGGER.debug("Failed to parse publish time {}", raw, error);
        }
        return null;
    }

    private long extractLong(String text) {
        Matcher matcher = Pattern.compile("(\\d+)").matcher(text);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        return 0L;
    }

    private String normalizeCityName(String city) {
        if (city == null) {
            return "";
        }

        String normalized = city.trim();
        String[] suffixes = {"特别行政区", "自治州", "自治区", "市辖区", "地区", "盟", "省", "市", "县"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : suffixes) {
                if (normalized.endsWith(suffix)) {
                    normalized = normalized.substring(0, normalized.length() - suffix.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return normalized;
    }

    private String dedupeKey(NewsArticle article) {
        String title = normalizeText(article.title());
        if (!title.isBlank()) {
            return title;
        }
        return normalizeText(article.url());
    }

    private String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String trimText(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.length() <= maxLength) {
            return cleaned;
        }
        return cleaned.substring(0, maxLength).trim();
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private record FetchResult(List<NewsArticle> items, boolean blocked) {
        private static FetchResult empty() {
            return new FetchResult(List.of(), false);
        }
    }

    private record SearchFetchResult(List<NewsArticle> items, boolean unavailable) {
    }

    private record RssFeed(String source, String url) {
    }

    public record NewsArticle(
            String title,
            String description,
            String content,
            String source,
            String url,
            OffsetDateTime publishedAt,
            String bucket,
            String city
    ) {
    }
}
