package com.bishe10.backend.service;

import com.bishe10.backend.model.NewsArticle;
import com.bishe10.backend.repository.NewsArticleRepository;
import com.bishe10.backend.repository.UserInterestProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class NewsCandidateExpandService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewsCandidateExpandService.class);
    private static final long CANDIDATE_TIME_BUDGET_MILLIS = 14000;
    private static final int MIN_CACHED_SCOPE_ITEMS = 8;
    private static final int MAX_CACHED_ARTICLE_AGE_DAYS = 7;
    private static final int MAX_FORCE_REFRESH_ARTICLE_AGE_DAYS = 3;
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Map<String, String> CITY_PROVINCES = Map.ofEntries(
            Map.entry("广州", "广东"),
            Map.entry("深圳", "广东"),
            Map.entry("上海", "上海"),
            Map.entry("北京", "北京"),
            Map.entry("杭州", "浙江"),
            Map.entry("南京", "江苏"),
            Map.entry("苏州", "江苏"),
            Map.entry("镇江", "江苏"),
            Map.entry("武汉", "湖北"),
            Map.entry("成都", "四川"),
            Map.entry("重庆", "重庆"),
            Map.entry("西安", "陕西"),
            Map.entry("天津", "天津"),
            Map.entry("厦门", "福建"),
            Map.entry("青岛", "山东"),
            Map.entry("长沙", "湖南"),
            Map.entry("郑州", "河南"),
            Map.entry("合肥", "安徽")
    );

    private final LocationResolver locationResolver;
    private final NewsSourceClient newsSourceClient;
    private final NewsArticleRepository articleRepository;
    private final UserInterestProfileRepository interestRepository;
    private final ArticleIdGenerator articleIdGenerator;

    public NewsCandidateExpandService(
            LocationResolver locationResolver,
            NewsSourceClient newsSourceClient,
            NewsArticleRepository articleRepository,
            UserInterestProfileRepository interestRepository,
            ArticleIdGenerator articleIdGenerator
    ) {
        this.locationResolver = locationResolver;
        this.newsSourceClient = newsSourceClient;
        this.articleRepository = articleRepository;
        this.interestRepository = interestRepository;
        this.articleIdGenerator = articleIdGenerator;
    }

    public CandidateResult buildCandidatePool(String userId, String city, String province, Double lat, Double lng, int size) {
        return buildCandidatePool(userId, city, province, lat, lng, size, false);
    }

    public CandidateResult buildCandidatePool(String userId, String city, String province, Double lat, Double lng, int size, boolean forceRefresh) {
        return buildCandidatePool(userId, city, province, lat, lng, size, forceRefresh, false);
    }

    public CandidateResult buildCandidatePool(
            String userId,
            String city,
            String province,
            Double lat,
            Double lng,
            int size,
            boolean forceRefresh,
            boolean cacheOnly
    ) {
        LocationResolver.ResolvedLocation resolved = locationResolver.resolve(lat, lng, city);
        String resolvedCity = normalizeCity(resolved.city());
        String resolvedProvince = normalizeProvince(province, resolvedCity);
        int target = Math.max(size * 2, 40);
        long deadlineMillis = System.currentTimeMillis() + CANDIDATE_TIME_BUDGET_MILLIS;

        LinkedHashMap<String, NewsArticle> candidates = new LinkedHashMap<>();
        addAll(candidates, fetchScopeWithCache(
                "CITY",
                resolvedCity,
                cityQueries(resolvedCity),
                Math.max(12, Math.min(size, 20)),
                true,
                resolvedCity,
                resolvedProvince,
                deadlineMillis,
                forceRefresh,
                cacheOnly
        ));

        if (candidates.size() < target && !isBlank(resolvedProvince) && !resolvedProvince.equals(resolvedCity)) {
            addAll(candidates, fetchScopeWithCache(
                    "PROVINCE",
                    resolvedProvince,
                    provinceQueries(resolvedProvince),
                    Math.max(8, Math.min(size, 12)),
                    true,
                    resolvedCity,
                    resolvedProvince,
                    deadlineMillis,
                    forceRefresh,
                    cacheOnly
            ));
        }

        if (candidates.size() < target) {
            addAll(candidates, fetchScopeWithCache(
                    "NATIONAL",
                    "全国",
                    nationalQueries(),
                    Math.max(8, Math.min(size, 12)),
                    false,
                    resolvedCity,
                    resolvedProvince,
                    deadlineMillis,
                    forceRefresh,
                    cacheOnly
            ));
        }

        if ((cacheOnly || hasCandidateFetchTime(deadlineMillis)) && !isBlank(userId) && candidates.size() < target) {
            for (String tag : topInterestTags(userId)) {
                addAll(candidates, fetchScopeWithCache(
                        "INTEREST",
                        tag,
                        interestQueries(resolvedCity, resolvedProvince, tag),
                        8,
                        false,
                        resolvedCity,
                        resolvedProvince,
                        deadlineMillis,
                        forceRefresh,
                        cacheOnly
                ));
                if (candidates.size() >= target || (!cacheOnly && !hasCandidateFetchTime(deadlineMillis))) {
                    break;
                }
            }
        }

        if (candidates.size() < target) {
            LOGGER.info(
                    "real news candidates are below target, but template fallback is disabled city={} real={} target={}",
                    resolvedCity,
                    candidates.size(),
                    target
            );
        }

        return new CandidateResult(
                resolvedCity,
                resolvedProvince,
                resolved.source(),
                resolved.permission(),
                resolved.updatedAt(),
                new ArrayList<>(candidates.values())
        );
    }

    public String provinceForCity(String city) {
        return normalizeProvince("", normalizeCity(city));
    }

    private List<NewsArticle> fetchScopeWithCache(
            String fetchScope,
            String scopeToken,
            List<String> queries,
            int limit,
            boolean requireScopeMatch,
            String city,
            String province,
            long deadlineMillis,
            boolean forceRefresh,
            boolean cacheOnly
    ) {
        LinkedHashMap<String, NewsArticle> items = new LinkedHashMap<>();

        if (cacheOnly) {
            addAll(items, freshEnough(cachedScope(fetchScope, city, province, limit), MAX_FORCE_REFRESH_ARTICLE_AGE_DAYS, false));
            return new ArrayList<>(items.values());
        }

        if (forceRefresh) {
            if (hasCandidateFetchTime(deadlineMillis)) {
                addAll(items, freshEnough(
                        fetchScope(fetchScope, scopeToken, freshnessQueries(scopeToken, queries), limit, requireScopeMatch, city, province),
                        MAX_FORCE_REFRESH_ARTICLE_AGE_DAYS,
                        false
                ));
            }

            if (items.size() < limit) {
                addAll(items, freshEnough(cachedScope(fetchScope, city, province, limit), MAX_FORCE_REFRESH_ARTICLE_AGE_DAYS, false));
            }
            return new ArrayList<>(items.values());
        }

        addAll(items, freshEnough(cachedScope(fetchScope, city, province, limit), MAX_CACHED_ARTICLE_AGE_DAYS));
        if (items.size() >= Math.min(limit, MIN_CACHED_SCOPE_ITEMS)) {
            return new ArrayList<>(items.values());
        }

        if (items.size() < limit) {
            if (hasCandidateFetchTime(deadlineMillis)) {
                addAll(items, freshEnough(
                        fetchScope(fetchScope, scopeToken, queries, limit - items.size(), requireScopeMatch, city, province),
                        MAX_CACHED_ARTICLE_AGE_DAYS
                ));
            } else {
                LOGGER.info("skip live news fetch because recommendation response is returning quickly scope={}", fetchScope);
            }
        }
        return new ArrayList<>(items.values());
    }

    private boolean hasCandidateFetchTime(long deadlineMillis) {
        return System.currentTimeMillis() < deadlineMillis - 500;
    }

    private List<NewsArticle> fetchScope(
            String fetchScope,
            String scopeToken,
            List<String> queries,
            int limit,
            boolean requireScopeMatch,
            String city,
            String province
    ) {
        try {
            return newsSourceClient.fetchByQueries(scopeToken, queries, limit, requireScopeMatch).stream()
                    .map(raw -> toArticle(raw, fetchScope, city, province))
                    .toList();
        } catch (Exception error) {
            LOGGER.warn("fetch news scope failed scope={} token={}", fetchScope, scopeToken, error);
            return List.of();
        }
    }

    private List<NewsArticle> cachedScope(String fetchScope, String city, String province, int limit) {
        try {
            List<NewsArticle> cached = articleRepository.findRecentByScope(fetchScope, city, province, limit);
            if (!cached.isEmpty()) {
                LOGGER.info("news cache supplied {} items for scope={}", cached.size(), fetchScope);
            }
            return cached;
        } catch (SQLException error) {
            LOGGER.debug("load cached news failed scope={}", fetchScope, error);
            return List.of();
        }
    }

    private List<String> freshnessQueries(String scopeToken, List<String> baseQueries) {
        String token = isBlank(scopeToken) ? "新闻" : scopeToken.trim();
        LocalDate today = LocalDate.now(CHINA_ZONE);
        String monthDay = today.getMonthValue() + "月" + today.getDayOfMonth() + "日";
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(token + " " + monthDay + " 新闻");
        queries.add(token + " 最新 新闻");
        queries.add(token + " 今日 新闻");
        queries.add(token + " 刚刚 新闻");
        if (baseQueries != null) {
            queries.addAll(baseQueries);
        }
        return new ArrayList<>(queries);
    }

    private List<NewsArticle> freshEnough(List<NewsArticle> articles, int maxAgeDays) {
        return freshEnough(articles, maxAgeDays, true);
    }

    private List<NewsArticle> freshEnough(List<NewsArticle> articles, int maxAgeDays, boolean includeUnknownTime) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        OffsetDateTime now = OffsetDateTime.now(CHINA_ZONE);
        return articles.stream()
                .filter(article -> isFreshEnough(article.publishTime(), now, maxAgeDays, includeUnknownTime))
                .toList();
    }

    private boolean isFreshEnough(OffsetDateTime publishTime, OffsetDateTime now, int maxAgeDays, boolean includeUnknownTime) {
        if (publishTime == null) {
            return includeUnknownTime;
        }
        long ageDays = ChronoUnit.DAYS.between(
                publishTime.atZoneSameInstant(CHINA_ZONE).toLocalDate(),
                now.atZoneSameInstant(CHINA_ZONE).toLocalDate()
        );
        return ageDays <= Math.max(1, maxAgeDays);
    }

    private NewsArticle toArticle(NewsSourceClient.NewsArticle raw, String fetchScope, String city, String province) {
        String category = category(raw.bucket(), raw.title(), raw.description());
        List<String> tags = tags(category, raw.title(), raw.description(), raw.content());
        OffsetDateTime publishTime = raw.publishedAt();
        String articleId = articleIdGenerator.articleId(raw.source(), raw.url(), raw.title(), publishTime);
        return new NewsArticle(
                articleId,
                safe(raw.title()),
                safe(raw.description()),
                safe(raw.content()),
                safe(raw.url()),
                safe(raw.source()),
                city,
                province,
                category,
                tags,
                publishTime,
                fetchScope,
                articleIdGenerator.contentHash(raw.title(), raw.description(), raw.content()),
                false
        );
    }

    private List<NewsArticle> fallbackArticles(String city, String province, int missing) {
        int count = Math.max(0, missing);
        OffsetDateTime now = OffsetDateTime.now();
        List<FallbackTemplate> templates = List.of(
                new FallbackTemplate("交通", "出门前关注常用线路无障碍电梯状态", "建议先确认地铁、公交、路口和施工绕行信息，再安排出行。", List.of("交通", "地铁", "无障碍")),
                new FallbackTemplate("无障碍", "本地无障碍服务信息整理", "系统将优先整理盲道、电梯、志愿陪同和便民服务等高频信息。", List.of("无障碍", "盲道", "志愿")),
                new FallbackTemplate("民生", "社区便民服务关注点", "可关注医院导诊、社区窗口、商场客服和公共服务开放时间。", List.of("民生", "社区", "服务")),
                new FallbackTemplate("交通", "晚间出行照明与路口提醒", "晚间出行建议优先选择熟悉路线，并确认路口语音提示和照明情况。", List.of("交通", "路口", "安全"))
        );
        List<NewsArticle> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            FallbackTemplate template = templates.get(index % templates.size());
            String title = city + " " + template.title();
            String articleId = articleIdGenerator.articleId("SYSTEM_TEMPLATE", "", title, now.minusMinutes(index));
            items.add(new NewsArticle(
                    articleId,
                    title,
                    template.summary(),
                    template.summary(),
                    "",
                    "SYSTEM_TEMPLATE",
                    city,
                    province,
                    template.category(),
                    template.tags(),
                    now.minusMinutes(index),
                    "FALLBACK",
                    articleIdGenerator.contentHash(title, template.summary(), template.summary()),
                    true
            ));
        }
        return items;
    }

    private void addAll(Map<String, NewsArticle> target, List<NewsArticle> items) {
        for (NewsArticle item : items) {
            target.putIfAbsent(item.articleId(), item);
        }
    }

    private List<String> cityQueries(String city) {
        return List.of(
                city + " 新闻",
                city + " 交通",
                city + " 无障碍",
                city + " 民生",
                city + " 公共服务"
        );
    }

    private List<String> provinceQueries(String province) {
        return List.of(
                province + " 新闻",
                province + " 交通",
                province + " 民生",
                province + " 无障碍"
        );
    }

    private List<String> nationalQueries() {
        return List.of("国内新闻", "交通新闻", "无障碍新闻", "民生新闻", "公共服务新闻");
    }

    private List<String> interestQueries(String city, String province, String tag) {
        List<String> queries = new ArrayList<>();
        if (!isBlank(city)) queries.add(city + " " + tag);
        if (!isBlank(province) && !province.equals(city)) queries.add(province + " " + tag);
        queries.add(tag + " 新闻");
        return queries;
    }

    private List<String> topInterestTags(String userId) {
        try {
            return interestRepository.findTopPositiveValues(userId, "TAG", 3);
        } catch (SQLException error) {
            LOGGER.warn("load top interest tags failed userId={}", userId, error);
            return List.of();
        }
    }

    private String normalizeProvince(String province, String city) {
        if (!isBlank(province)) {
            return normalizeCity(province);
        }
        return CITY_PROVINCES.getOrDefault(city, "");
    }

    private String normalizeCity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "上海";
        }
        return raw.trim()
                .replace("特别行政区", "")
                .replace("自治区", "")
                .replace("自治州", "")
                .replace("地区", "")
                .replace("市辖区", "")
                .replace("市", "")
                .replace("省", "");
    }

    private String category(String bucket, String title, String summary) {
        String text = (safe(bucket) + " " + safe(title) + " " + safe(summary)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "travel", "交通", "地铁", "公交", "出行", "路口", "电梯")) {
            return "交通";
        }
        if (containsAny(text, "无障碍", "盲道", "视障", "助残")) {
            return "无障碍";
        }
        if (containsAny(text, "社区", "医院", "便民", "服务", "民生")) {
            return "民生";
        }
        return "民生";
    }

    private List<String> tags(String category, String title, String summary, String content) {
        String text = (safe(title) + " " + safe(summary) + " " + safe(content)).toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();
        tags.add(category);
        addTagIf(tags, text, "交通", "交通", "地铁", "公交", "出行", "路口", "施工", "电梯");
        addTagIf(tags, text, "无障碍", "无障碍", "盲道", "视障", "助残", "志愿");
        addTagIf(tags, text, "民生", "民生", "社区", "医院", "便民", "公共服务");
        addTagIf(tags, text, "天气", "天气", "暴雨", "高温", "台风", "空气");
        return tags.stream().distinct().limit(6).toList();
    }

    private void addTagIf(List<String> tags, String text, String tag, String... keywords) {
        if (containsAny(text, keywords)) {
            tags.add(tag);
        }
    }

    private boolean containsAny(String text, String... keywords) {
        if (text == null) return false;
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record CandidateResult(
            String city,
            String province,
            String source,
            String permission,
            String updatedAt,
            List<NewsArticle> articles
    ) {
    }

    private record FallbackTemplate(String category, String title, String summary, List<String> tags) {
    }
}
