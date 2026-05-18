package com.bishe10.backend.service;

import com.bishe10.backend.dto.FeedbackResponse;
import com.bishe10.backend.dto.NewsRecommendItem;
import com.bishe10.backend.dto.NewsRecommendResponse;
import com.bishe10.backend.dto.UserNewsProfileResponse;
import com.bishe10.backend.model.NewsArticle;
import com.bishe10.backend.model.UserBlockRule;
import com.bishe10.backend.model.UserInterestProfile;
import com.bishe10.backend.repository.NewsArticleRepository;
import com.bishe10.backend.repository.UserNewsFeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PersonalizedNewsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonalizedNewsService.class);

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 40;
    private static final Set<String> VALID_ACTIONS = Set.of(
            "LIKE", "DISLIKE", "FAVORITE", "SKIP", "NOT_INTERESTED", "BLOCK_SIMILAR", "VIEW"
    );

    private final NewsCandidateExpandService candidateExpandService;
    private final NewsArticleRepository articleRepository;
    private final UserNewsFeedbackRepository feedbackRepository;
    private final UserProfileService userProfileService;
    private final RuleScoreService ruleScoreService;
    private final PersonalizationService personalizationService;
    private final WeatherService weatherService;

    public PersonalizedNewsService(
            NewsCandidateExpandService candidateExpandService,
            NewsArticleRepository articleRepository,
            UserNewsFeedbackRepository feedbackRepository,
            UserProfileService userProfileService,
            RuleScoreService ruleScoreService,
            PersonalizationService personalizationService,
            WeatherService weatherService
    ) {
        this.candidateExpandService = candidateExpandService;
        this.articleRepository = articleRepository;
        this.feedbackRepository = feedbackRepository;
        this.userProfileService = userProfileService;
        this.ruleScoreService = ruleScoreService;
        this.personalizationService = personalizationService;
        this.weatherService = weatherService;
    }

    public NewsRecommendResponse recommend(String userId, String city, String province, Double lat, Double lng, Integer size, String cursor) {
        return recommend(userId, city, province, lat, lng, size, cursor, false);
    }

    public NewsRecommendResponse recommend(String userId, String city, String province, Double lat, Double lng, Integer size, String cursor, boolean force) {
        return recommend(userId, city, province, lat, lng, size, cursor, force, false);
    }

    public NewsRecommendResponse recommend(
            String userId,
            String city,
            String province,
            Double lat,
            Double lng,
            Integer size,
            String cursor,
            boolean force,
            boolean cacheOnly
    ) {
        int limit = Math.max(1, Math.min(MAX_SIZE, size == null ? DEFAULT_SIZE : size));
        boolean personalized = userId != null && !userId.isBlank();
        NewsCandidateExpandService.CandidateResult candidates = candidateExpandService.buildCandidatePool(
                userId,
                city,
                province,
                lat,
                lng,
                limit,
                force,
                cacheOnly
        );

        try {
            articleRepository.upsertAll(candidates.articles());
        } catch (SQLException error) {
            LOGGER.warn("cache news articles failed", error);
        }

        List<UserInterestProfile> interests = personalized ? userProfileService.interests(userId) : List.of();
        List<UserBlockRule> blockRules = personalized ? userProfileService.blockRules(userId) : List.of();
        Map<String, Set<String>> actionMap = personalized ? loadActions(userId, candidates.articles()) : Map.of();
        OffsetDateTime now = OffsetDateTime.now();
        Set<String> skippedByCursor = parseCursor(cursor);

        List<ScoredArticle> scored = new ArrayList<>();
        for (NewsArticle article : candidates.articles()) {
            if (isTemplateArticle(article)) {
                continue;
            }
            if (skippedByCursor.contains(article.articleId())) {
                continue;
            }
            Set<String> actions = actionMap.getOrDefault(article.articleId(), Set.of());
            if (personalized && personalizationService.shouldFilter(article, actions, blockRules)) {
                continue;
            }

            RuleScoreService.ScoreResult base = ruleScoreService.score(article, candidates.city(), candidates.province(), now);
            PersonalizationService.ScoreResult personal = personalized
                    ? personalizationService.score(article, interests, actions)
                    : new PersonalizationService.ScoreResult(0, List.of());
            double scopeBonus = personalizationService.scopeBonus(article.fetchScope());
            double finalScore = base.score() + personal.score() + scopeBonus;
            scored.add(new ScoredArticle(article, finalScore, base.score(), personal.score(), base.reasons(), personal.reasons(), actions));
        }

        scored.sort(
                Comparator.comparingDouble(ScoredArticle::finalScore).reversed()
                        .thenComparing(
                                item -> item.article().publishTime(),
                                Comparator.nullsLast(Comparator.reverseOrder())
                        )
        );
        List<ScoredArticle> selected = applyScopeQuota(scored, limit, personalized && !interests.isEmpty());
        List<NewsRecommendItem> items = selected.stream()
                .map(item -> toItem(item, personalized && interests.isEmpty()))
                .toList();

        Map<String, Object> locationContext = new LinkedHashMap<>();
        locationContext.put("city", candidates.city());
        locationContext.put("province", candidates.province());
        locationContext.put("source", candidates.source());
        locationContext.put("permission", candidates.permission());
        locationContext.put("updatedAt", candidates.updatedAt());

        boolean useGpsWeather = "gps".equals(candidates.source());
        Map<String, Object> weather = weatherService.buildLocalWeather(
                useGpsWeather ? lat : null,
                useGpsWeather ? lng : null,
                candidates.city(),
                force && !cacheOnly
        );
        return new NewsRecommendResponse(
                items,
                scored.size() > selected.size(),
                locationContext,
                weather,
                buildQuickEntries(candidates.city(), items, weather)
        );
    }

    private boolean isTemplateArticle(NewsArticle article) {
        if (article == null) {
            return true;
        }
        String source = article.source() == null ? "" : article.source().trim();
        String scope = article.fetchScope() == null ? "" : article.fetchScope().trim();
        return article.synthetic()
                || "SYSTEM_TEMPLATE".equalsIgnoreCase(source)
                || "AI_GENERATED".equalsIgnoreCase(source)
                || "FALLBACK".equalsIgnoreCase(scope);
    }

    public FeedbackResponse saveFeedback(String articleId, String userId, String action) {
        String normalizedUserId = userId == null ? "" : userId.trim();
        String normalizedAction = action == null ? "" : action.trim().toUpperCase();
        if (normalizedUserId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空。");
        }
        if (articleId == null || articleId.isBlank()) {
            throw new IllegalArgumentException("articleId 不能为空。");
        }
        if (!VALID_ACTIONS.contains(normalizedAction)) {
            throw new IllegalArgumentException("不支持的反馈类型。");
        }

        NewsArticle article = findArticle(articleId)
                .orElseThrow(() -> new IllegalArgumentException("新闻不存在，请先刷新推荐列表。"));

        try {
            feedbackRepository.save(normalizedUserId, articleId, normalizedAction);
        } catch (SQLException error) {
            LOGGER.warn("save user feedback failed userId={} articleId={} action={}", normalizedUserId, articleId, normalizedAction, error);
            throw new IllegalStateException("反馈保存失败，请确认数据库已初始化。");
        }

        List<FeedbackResponse.UpdatedProfile> updated = userProfileService.updateProfileByFeedback(normalizedUserId, article, normalizedAction);
        return new FeedbackResponse(true, "feedback saved", updated);
    }

    public UserNewsProfileResponse profile(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空。");
        }
        return userProfileService.profile(userId.trim());
    }

    public UserProfileService.ResetResult resetProfile(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId 不能为空。");
        }
        return userProfileService.resetToDefault(userId.trim());
    }

    private Optional<NewsArticle> findArticle(String articleId) {
        try {
            return articleRepository.findById(articleId);
        } catch (SQLException error) {
            LOGGER.warn("find news article failed articleId={}", articleId, error);
            return Optional.empty();
        }
    }

    private Map<String, Set<String>> loadActions(String userId, List<NewsArticle> articles) {
        try {
            return feedbackRepository.findActions(
                    userId,
                    articles.stream().map(NewsArticle::articleId).collect(Collectors.toCollection(LinkedHashSet::new))
            );
        } catch (SQLException error) {
            LOGGER.warn("load user news feedback failed userId={}", userId, error);
            return Map.of();
        }
    }

    private Set<String> parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String part : cursor.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                ids.add(trimmed);
            }
        }
        return ids;
    }

    private List<ScoredArticle> applyScopeQuota(List<ScoredArticle> scored, int size, boolean hasProfile) {
        Map<String, Integer> quotas = hasProfile
                ? quota(size, 0.40, 0.30, 0.15, 0.10, 0.05)
                : quota(size, 0.50, 0.00, 0.20, 0.20, 0.10);
        List<ScoredArticle> selected = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (ScoredArticle item : scored) {
            String scope = item.article().fetchScope();
            int quota = quotas.getOrDefault(scope, size);
            int current = counts.getOrDefault(scope, 0);
            if (current >= quota) {
                continue;
            }
            selected.add(item);
            used.add(item.article().articleId());
            counts.put(scope, current + 1);
            if (selected.size() >= size) {
                return selected;
            }
        }

        for (ScoredArticle item : scored) {
            if (selected.size() >= size) {
                break;
            }
            if (used.add(item.article().articleId())) {
                selected.add(item);
            }
        }
        return selected;
    }

    private Map<String, Integer> quota(int size, double city, double interest, double province, double national, double fallback) {
        Map<String, Integer> quotas = new LinkedHashMap<>();
        quotas.put("CITY", Math.max(1, (int) Math.ceil(size * city)));
        quotas.put("INTEREST", (int) Math.ceil(size * interest));
        quotas.put("PROVINCE", (int) Math.ceil(size * province));
        quotas.put("NATIONAL", (int) Math.ceil(size * national));
        quotas.put("FALLBACK", Math.max(1, (int) Math.ceil(size * fallback)));
        return quotas;
    }

    private NewsRecommendItem toItem(ScoredArticle scored, boolean coldStart) {
        NewsArticle article = scored.article();
        List<String> reasons = buildReasons(scored, coldStart);
        return new NewsRecommendItem(
                article.articleId(),
                article.title(),
                article.summary(),
                article.content(),
                article.url(),
                article.source(),
                article.city(),
                article.province(),
                article.category(),
                article.tags(),
                article.publishTime() == null ? "" : article.publishTime().toString(),
                formatTime(article.publishTime()),
                round(scored.finalScore()),
                round(scored.baseScore()),
                round(scored.personalScore()),
                article.fetchScope(),
                reasons,
                Map.of(
                        "liked", scored.actions().contains("LIKE"),
                        "disliked", scored.actions().contains("DISLIKE"),
                        "favorited", scored.actions().contains("FAVORITE")
                ),
                article.synthetic()
        );
    }

    private List<String> buildReasons(ScoredArticle scored, boolean coldStart) {
        List<String> reasons = new ArrayList<>();
        reasons.addAll(scored.personalReasons());
        reasons.addAll(scored.baseReasons());
        String scope = scored.article().fetchScope();
        if ("INTEREST".equals(scope)) {
            reasons.add("根据你的兴趣扩展推荐");
        } else if ("CITY".equals(scope)) {
            reasons.add("优先来自当前城市新闻池");
        } else if ("PROVINCE".equals(scope)) {
            reasons.add("城市新闻不足时补充同省内容");
        } else if ("NATIONAL".equals(scope)) {
            reasons.add("补充全国热点资讯");
        }
        if (coldStart) {
            reasons.add("你还没有足够的反馈，系统将优先使用城市和时间规则");
        }
        return reasons.stream()
                .filter(reason -> reason != null && !reason.isBlank())
                .distinct()
                .limit(4)
                .toList();
    }

    private String formatTime(OffsetDateTime publishTime) {
        if (publishTime == null) {
            return "刚刚";
        }
        long hours = Math.abs(ChronoUnit.HOURS.between(publishTime, OffsetDateTime.now()));
        if (hours < 1) return "刚刚";
        if (hours < 24) return hours + " 小时前";
        long days = Math.abs(ChronoUnit.DAYS.between(publishTime.toLocalDate(), OffsetDateTime.now().toLocalDate()));
        if (days <= 1) return "昨天";
        return publishTime.toLocalDate().toString();
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private List<Map<String, Object>> buildQuickEntries(String city, List<NewsRecommendItem> items, Map<String, Object> weather) {
        return List.of(
                buildTravelEntry(weather),
                buildBulletinEntry(city, items)
        );
    }

    private Map<String, Object> buildTravelEntry(Map<String, Object> weather) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "travel");
        item.put("label", "出行");
        item.put("meta", travelMeta(weather));
        item.put("hint", travelHint(weather));
        item.put("detail", asText(weather.get("travel_advice"), "当前出行建议正在整理，请优先确认天气、路面和常用路线。"));
        item.put("spokenText", asText(weather.get("spoken_text"), item.get("detail")));
        item.put("weather", weather);
        return item;
    }

    private Map<String, Object> buildBulletinEntry(String city, List<NewsRecommendItem> items) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "bulletin");
        item.put("label", "快讯");
        item.put("meta", city + " 个性化快讯");
        item.put("hint", items.isEmpty() ? "正在扩展同省和全国热点新闻" : items.get(0).title());
        item.put("detail", buildBulletinDetail(items));
        item.put("spokenText", buildBulletinSpoken(items));
        return item;
    }

    private String travelHint(Map<String, Object> weather) {
        String weatherText = asText(weather.get("weather_text"), "天气待确认");
        String temperature = String.valueOf(weather.getOrDefault("temperature", ""));
        String alert = asText(weather.get("alert"), "");
        String base = temperature.isBlank() ? weatherText : weatherText + " · " + temperature + "℃";
        return alert.isBlank() ? base : base + " · " + alert;
    }

    private String travelMeta(Map<String, Object> weather) {
        String air = asText(weather.get("air_quality"), "");
        String wind = asText(weather.get("wind_direction"), "");
        Object windSpeed = weather.get("wind_speed");
        List<String> parts = new ArrayList<>();
        if (!air.isBlank()) parts.add("空气 " + air);
        if (!wind.isBlank()) parts.add(wind + windSpeed + "km/h");
        String update = asText(weather.get("update_time"), "");
        if (!update.isBlank()) parts.add(update + " 更新");
        return parts.isEmpty() ? "天气与出行建议" : String.join(" · ", parts);
    }

    private String buildBulletinDetail(List<NewsRecommendItem> items) {
        if (items.isEmpty()) {
            return "暂时没有更多本地快讯，系统会继续扩展同省和全国热点。";
        }
        return items.stream()
                .limit(2)
                .map(item -> item.title())
                .filter(title -> title != null && !title.isBlank())
                .collect(Collectors.joining("；"));
    }

    private String buildBulletinSpoken(List<NewsRecommendItem> items) {
        if (items.isEmpty()) {
            return "暂时没有更多本地快讯，系统会继续扩展同省和全国热点。";
        }
        return items.stream()
                .limit(3)
                .map(item -> {
                    String text = item.summary() == null || item.summary().isBlank() ? item.title() : item.summary();
                    return text == null ? "" : text.trim();
                })
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("。"));
    }

    private String asText(Object value, Object fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback == null ? "" : String.valueOf(fallback);
        }
        return String.valueOf(value).trim();
    }

    private record ScoredArticle(
            NewsArticle article,
            double finalScore,
            double baseScore,
            double personalScore,
            List<String> baseReasons,
            List<String> personalReasons,
            Set<String> actions
    ) {
    }
}
