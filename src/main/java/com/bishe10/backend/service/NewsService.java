package com.bishe10.backend.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class NewsService {

    private static final long CACHE_TTL_MINUTES = 15;
    private static final int NEWS_ITEM_LIMIT = 30;
    private static final int MIN_REAL_NEWS_COUNT = 8;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 40;
    private static final int DAY_BUCKET_SPAN = 5;  // today + past 4 days
    private static final int MIN_ITEMS_PER_DAY = 2;
    private static final int MAX_ITEMS_PER_DAY = 6;
    private static final double MIN_LIVE_ARTICLE_SCORE = 2.6;
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    private final LocationResolver locationResolver;
    private final NewsSourceClient newsSourceClient;
    private final LlmService llmService;
    private final WeatherService weatherService;
    private final Map<String, CacheEntry> cityCache = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> interpretCache = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> synthesizedDayCache = new ConcurrentHashMap<>();

    public NewsService(
            LocationResolver locationResolver,
            NewsSourceClient newsSourceClient,
            LlmService llmService,
            WeatherService weatherService
    ) {
        this.locationResolver = locationResolver;
        this.newsSourceClient = newsSourceClient;
        this.llmService = llmService;
        this.weatherService = weatherService;
    }

    public Map<String, Object> buildRecommendations(String city, Double lat, Double lng) {
        return buildRecommendations(city, lat, lng, 1, DEFAULT_PAGE_SIZE, false);
    }

    public Map<String, Object> buildRecommendations(String city, Double lat, Double lng, Integer page, Integer pageSize) {
        return buildRecommendations(city, lat, lng, page, pageSize, false);
    }

    public Map<String, Object> interpretArticle(String title, String summary, String content, String source, String category) {
        String cacheKey = hash(title + "|" + summary + "|" + content);
        Map<String, Object> cached = interpretCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "template");
        result.put("provider", llmService.getProviderLabel());

        String fallbackBrief = buildFallbackBrief(title, summary, category);
        List<String> fallbackPoints = buildFallbackPoints(summary, content);
        String fallbackSpoken = buildFallbackSpoken(title, summary);

        java.util.Optional<Map<String, Object>> llm = llmService.interpretNews(title, summary, content, source, category);
        if (llm.isPresent()) {
            Map<String, Object> payload = llm.get();
            String brief = toPlainString(payload.get("brief"));
            Object keyPointsRaw = payload.get("keyPoints");
            List<String> keyPoints = new ArrayList<>();
            if (keyPointsRaw instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        String s = item.toString().trim();
                        if (!s.isBlank()) {
                            keyPoints.add(s);
                        }
                    }
                }
            }
            String spoken = toPlainString(payload.get("spokenText"));
            if (!brief.isBlank() && !keyPoints.isEmpty()) {
                result.put("mode", "llm");
                result.put("brief", brief);
                result.put("keyPoints", keyPoints);
                result.put("spokenText", spoken.isBlank() ? fallbackSpoken : spoken);
                interpretCache.put(cacheKey, result);
                return result;
            }
        }

        result.put("brief", fallbackBrief);
        result.put("keyPoints", fallbackPoints);
        result.put("spokenText", fallbackSpoken);
        interpretCache.put(cacheKey, result);
        return result;
    }

    private String toPlainString(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private String buildFallbackBrief(String title, String summary, String category) {
        String base = summary == null || summary.isBlank() ? title : summary;
        if (base == null) base = "";
        base = base.trim();
        String prefix = switch (category == null ? "" : category) {
            case "出行提醒" -> "出行相关：";
            case "社区资讯" -> "社区资讯：";
            case "民生快讯" -> "民生快讯：";
            default -> "";
        };
        return trimText(prefix + base, 120);
    }

    private List<String> buildFallbackPoints(String summary, String content) {
        List<String> points = new ArrayList<>();
        String base = summary == null || summary.isBlank() ? content : summary;
        if (base != null && !base.isBlank()) {
            points.add(trimText(base.trim(), 24));
        }
        points.add("出门前可先确认路线和无障碍设施");
        points.add("如需语音播报可点击朗读按钮");
        return points;
    }

    private String buildFallbackSpoken(String title, String summary) {
        String t = title == null ? "" : title.trim();
        String s = summary == null ? "" : summary.trim();
        if (t.isBlank() && s.isBlank()) return "资讯已更新，请点击查看。";
        // Prefer content; only fall back to title when summary is missing.
        if (!s.isBlank()) return trimText(s, 140);
        return trimText(t, 90);
    }

    private String hash(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", bytes[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public Map<String, Object> buildRecommendations(String city, Double lat, Double lng, Integer page, Integer pageSize, boolean force) {
        LocationResolver.ResolvedLocation resolvedLocation = locationResolver.resolve(lat, lng, city);
        String normalizedCity = resolvedLocation.city();
        OffsetDateTime now = OffsetDateTime.now();

        int effectivePage = Math.max(1, page == null ? 1 : page);
        int effectivePageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize == null ? DEFAULT_PAGE_SIZE : pageSize));

        if (force) {
            cityCache.remove(normalizedCity);
        }

        Map<String, Object> fullPayload = getCityPayload(normalizedCity, now);
        return paginatePayload(fullPayload, effectivePage, effectivePageSize, resolvedLocation, lat, lng);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getCityPayload(String normalizedCity, OffsetDateTime now) {
        CacheEntry cached = cityCache.get(normalizedCity);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return cached.payload();
        }

        Map<String, Object> headline = new LinkedHashMap<>();
        headline.put("title", normalizedCity + " 无障碍资讯速览");
        headline.put("subtitle", buildHeadlineSubtitle());
        headline.put("date", LocalDate.now().toString());
        headline.put("spokenText", normalizedCity + "无障碍资讯速览。" + buildHeadlineSubtitle());

        List<Map<String, Object>> rawNewsList = buildNewsList(normalizedCity, now);
        sortNewsListByPublishedAtDesc(rawNewsList);
        // Group into last-5-day buckets, fill sparse days with LLM / template items.
        List<Map<String, Object>> dayGroups = buildDayGroups(normalizedCity, rawNewsList, now);
        List<Map<String, Object>> newsList = flattenDayGroups(dayGroups);
        sortNewsListByPublishedAtDesc(newsList);
        resequenceNewsList(newsList);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("headline", headline);
        payload.put("newsList", newsList);
        payload.put("dayGroups", dayGroups);
        payload.put("feedMode", "city-contextual-digest");

        cityCache.put(normalizedCity, new CacheEntry(
                copyPayload(payload),
                now.plusMinutes(CACHE_TTL_MINUTES)
        ));
        return copyPayload(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paginatePayload(
            Map<String, Object> fullPayload,
            int page,
            int pageSize,
            LocationResolver.ResolvedLocation loc,
            Double lat,
            Double lng
    ) {
        Map<String, Object> out = new LinkedHashMap<>(fullPayload);
        List<Map<String, Object>> fullList = (List<Map<String, Object>>) fullPayload.get("newsList");
        int total = fullList.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) total / pageSize));
        int clampedPage = Math.max(1, Math.min(page, totalPages));
        int fromIndex = (clampedPage - 1) * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<Map<String, Object>> slice = fromIndex >= total
                ? new ArrayList<>()
                : new ArrayList<>(fullList.subList(fromIndex, toIndex));
        out.put("newsList", slice);
        // Re-build dayGroups for just the sliced items so frontend can render calendar grouping.
        out.put("dayGroups", regroupByDate(slice, fullPayload));

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", clampedPage);
        pagination.put("pageSize", pageSize);
        pagination.put("total", total);
        pagination.put("totalPages", totalPages);
        pagination.put("hasMore", clampedPage < totalPages);
        out.put("pagination", pagination);
        out.put("locationContext", locationContext(loc));
        boolean useGpsWeather = "gps".equals(loc.source());
        Map<String, Object> weather = weatherService.buildLocalWeather(
                useGpsWeather ? lat : null,
                useGpsWeather ? lng : null,
                loc.city()
        );
        out.put("weather", weather);
        out.put("quickEntries", buildQuickEntries(loc.city(), fullList, weather));
        return out;
    }

    private String buildHeadlineSubtitle() {
        int hour = LocalTime.now().getHour();
        if (hour < 11) {
            return "早间出行与本地服务";
        }
        if (hour < 18) {
            return "白天外出与路径提示";
        }
        return "晚间返程与安全提示";
    }

    private Map<String, Object> quickEntry(String id, String label, String hint) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("label", label);
        item.put("hint", hint);
        return item;
    }

    private List<Map<String, Object>> buildQuickEntries(String city, List<Map<String, Object>> newsList, Map<String, Object> weather) {
        List<Map<String, Object>> entries = new ArrayList<>();
        entries.add(buildTravelEntry(city, weather));
        entries.add(buildBulletinEntry(city, newsList));
        return entries;
    }

    private Map<String, Object> buildTravelEntry(String city, Map<String, Object> weather) {
        Map<String, Object> item = quickEntry("travel", "出行", travelHint(weather));
        item.put("detail", asText(weather.get("travel_advice")));
        item.put("meta", travelMeta(weather));
        item.put("spokenText", asText(weather.get("spoken_text")));
        item.put("weather", weather);
        return item;
    }

    private Map<String, Object> buildBulletinEntry(String city, List<Map<String, Object>> newsList) {
        Map<String, Object> item = quickEntry("bulletin", "快讯", bulletinHint(newsList));
        item.put("detail", bulletinDetail(newsList));
        item.put("meta", city + " · " + Math.min(3, newsList.size()) + " 条优先播报");
        item.put("spokenText", bulletinSpokenText(city, newsList));
        return item;
    }

    private String travelHint(Map<String, Object> weather) {
        String weatherText = asText(weather.get("weather_text"));
        int temperature = intValue(weather.get("temperature"));
        String alert = asText(weather.get("alert"));
        if (!alert.isBlank() && !"当前无特别预警".equals(alert)) {
            return weatherText + " · " + temperature + "℃ · " + alert;
        }
        return weatherText + " · " + temperature + "℃";
    }

    private String travelMeta(Map<String, Object> weather) {
        String airQuality = asText(weather.get("air_quality"));
        String windDirection = asText(weather.get("wind_direction"));
        int windSpeed = intValue(weather.get("wind_speed"));
        String updateTime = asText(weather.get("update_time"));
        String compactTime = updateTime.length() >= 16 ? updateTime.substring(11, 16) : updateTime;
        return "空气" + airQuality + " · " + windDirection + " " + windSpeed + "km/h · " + compactTime + "更新";
    }

    private String bulletinHint(List<Map<String, Object>> newsList) {
        if (newsList.isEmpty()) {
            return "本地新闻正在整理中";
        }
        Map<String, Object> first = newsList.get(0);
        return trimText(nonBlank(asText(first.get("title")), asText(first.get("summary")), "本地新闻正在整理中"), 42);
    }

    private String bulletinDetail(List<Map<String, Object>> newsList) {
        if (newsList.size() < 2) {
            return newsList.isEmpty()
                    ? "稍后将补充本地民生、交通和社区快讯。"
                    : trimText(asText(newsList.get(0).get("summary")), 72);
        }
        return trimText(asText(newsList.get(1).get("summary")), 72);
    }

    private String bulletinSpokenText(String city, List<Map<String, Object>> newsList) {
        if (newsList.isEmpty()) {
            return city + "本地快讯正在整理中，请稍后刷新查看。";
        }

        StringBuilder builder = new StringBuilder(city).append("快讯。");
        int limit = Math.min(3, newsList.size());
        for (int index = 0; index < limit; index++) {
            Map<String, Object> item = newsList.get(index);
            String spokenText = nonBlank(asText(item.get("spokenText")), asText(item.get("summary")), asText(item.get("title")));
            if (!spokenText.isBlank()) {
                builder.append(spokenText);
                if (!spokenText.endsWith("。")) {
                    builder.append("。");
                }
            }
        }
        return trimText(builder.toString(), 220);
    }

    //筛选新闻、打分、去重、分桶，优先保证每个类别都有内容，同时避免单一类别过多占位（如志愿服务占满苏州的推荐位）。
    private List<Map<String, Object>> buildNewsList(String city, OffsetDateTime now) {
        List<RankedArticle> ranked = rankArticles(city, now, newsSourceClient.fetchByCity(city));

        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> usedTitles = new HashSet<>();

        // Balance buckets: cap each bucket at ~40% of the pool to avoid a single
        // category dominating the whole feed (e.g. 志愿服务 swamping 苏州's feed).
        int bucketCap = Math.max(4, (int) Math.round(NEWS_ITEM_LIMIT * 0.45));
        Map<String, Integer> bucketCount = new LinkedHashMap<>();
        List<RankedArticle> overflow = new ArrayList<>();

        for (RankedArticle rankedArticle : ranked) {
            if (items.size() >= NEWS_ITEM_LIMIT) break;
            NewsSourceClient.NewsArticle article = rankedArticle.article();
            String bucket = article.bucket() == null ? "service" : article.bucket();
            int currentBucket = bucketCount.getOrDefault(bucket, 0);
            if (currentBucket >= bucketCap) {
                overflow.add(rankedArticle);
                continue;
            }
            if (tryAddArticle(items, usedTitles, article, now, city)) {
                bucketCount.put(bucket, currentBucket + 1);
            }
        }

        // Fill remaining slots from overflow if pool not yet full.
        for (RankedArticle rankedArticle : overflow) {
            if (items.size() >= NEWS_ITEM_LIMIT) break;
            tryAddArticle(items, usedTitles, rankedArticle.article(), now, city);
        }

        int realNewsCount = items.size();

        if (items.size() < NEWS_ITEM_LIMIT) {
            items.addAll(buildFallbackSupplement(city, now, items.size() + 1, NEWS_ITEM_LIMIT - items.size(), usedTitles));
        }

        ensureMinimumRealNewsWindow(items, realNewsCount);
        sortNewsListByPublishedAtDesc(items);
        resequenceNewsList(items);

        return items;
    }

    private boolean tryAddArticle(
            List<Map<String, Object>> items,
            Set<String> usedTitles,
            NewsSourceClient.NewsArticle article,
            OffsetDateTime now,
            String city
    ) {
        String category = mapCategory(article.bucket());
        String summary = nonBlank(article.description(), article.content(), "本地资讯");
        String title = nonBlank(article.title(), summary, city + " 本地资讯更新");
        String source = nonBlank(article.source(), city + " 资讯聚合");
        String normalizedTitle = normalizeText(title);
        if (!normalizedTitle.isBlank() && !usedTitles.add(normalizedTitle)) {
            return false;
        }
        Map<String, Object> item = newsItem(
                items.size() + 1,
                category,
                trimText(title, 48),
                trimText(summary, 110),
                formatTime(article.publishedAt(), now),
                source,
                trimText(nonBlank(article.content(), article.description(), summary), 360),
                buildSpokenText(category, title, summary),
                article.url() == null ? "" : article.url()
        );
        // Attach a canonical date so the frontend calendar grouping is stable.
        OffsetDateTime published = article.publishedAt();
        LocalDate localDate = (published == null ? LocalDate.now(SHANGHAI_ZONE) : published.atZoneSameInstant(SHANGHAI_ZONE).toLocalDate());
        item.put("publishedDate", localDate.toString());
        item.put("publishedAtEpoch", published == null ? 0L : published.toInstant().toEpochMilli());
        item.put("synthetic", false);
        items.add(item);
        return true;
    }

    // === Day-bucket grouping (calendar view) ===

    private List<Map<String, Object>> buildDayGroups(String city, List<Map<String, Object>> flat, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(SHANGHAI_ZONE).toLocalDate();
        // Build 5 day slots, most recent first.
        LinkedHashMap<String, List<Map<String, Object>>> slots = new LinkedHashMap<>();
        for (int i = 0; i < DAY_BUCKET_SPAN; i++) {
            slots.put(today.minusDays(i).toString(), new ArrayList<>());
        }

        // Bucket each news item into its slot. Items older than 4 days roll into the oldest slot
        // (keeps otherwise-discarded content visible and ensures every day has something).
        String oldestKey = today.minusDays(DAY_BUCKET_SPAN - 1).toString();
        for (Map<String, Object> item : flat) {
            String date = asText(item.get("publishedDate"));
            if (date.isBlank()) {
                date = today.toString();
            }
            List<Map<String, Object>> bucket = slots.get(date);
            if (bucket == null) {
                // Older than the window — merge into the oldest slot and REWRITE its
                // publishedDate so pagination-level regrouping doesn't fragment them.
                item.put("publishedDate", oldestKey);
                bucket = slots.get(oldestKey);
            }
            bucket.add(item);
        }

        // Cap each slot so a busy day doesn't push thin days off page 1.
        for (Map.Entry<String, List<Map<String, Object>>> entry : slots.entrySet()) {
            List<Map<String, Object>> list = entry.getValue();
            if (list.size() > MAX_ITEMS_PER_DAY) {
                entry.setValue(new ArrayList<>(list.subList(0, MAX_ITEMS_PER_DAY)));
            }
        }

        boolean allowLlmDaySynthesis = flat.stream()
                .noneMatch(item -> !Boolean.TRUE.equals(item.get("synthetic")));

        // Fill sparse days with local stubs, and only invoke the LLM when the whole
        // feed has no real news. This keeps the endpoint responsive once live news exists.
        for (Map.Entry<String, List<Map<String, Object>>> entry : slots.entrySet()) {
            List<Map<String, Object>> list = entry.getValue();
            int missing = MIN_ITEMS_PER_DAY - list.size();
            if (missing > 0) {
                List<Map<String, Object>> synthesized = synthesizeDayEntries(city, entry.getKey(), missing, list, allowLlmDaySynthesis);
                list.addAll(synthesized);
            }
        }

        // Build the groups output.
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : slots.entrySet()) {
            LocalDate date = LocalDate.parse(entry.getKey());
            long diff = ChronoUnit.DAYS.between(date, today);
            String label = dayLabel(diff);
            List<Map<String, Object>> list = entry.getValue();
            // Attach dayLabel to each item so the flat list can show per-item context if needed.
            for (Map<String, Object> item : list) {
                item.put("dayLabel", label);
                if (!item.containsKey("publishedDate") || asText(item.get("publishedDate")).isBlank()) {
                    item.put("publishedDate", entry.getKey());
                }
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("date", entry.getKey());
            group.put("label", label);
            group.put("count", list.size());
            group.put("items", list);
            groups.add(group);
        }
        return groups;
    }

    private List<Map<String, Object>> flattenDayGroups(List<Map<String, Object>> dayGroups) {
        List<Map<String, Object>> out = new ArrayList<>();
        int seq = 1;
        for (Map<String, Object> group : dayGroups) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("items");
            if (items == null) continue;
            for (Map<String, Object> it : items) {
                Map<String, Object> copy = new LinkedHashMap<>(it);
                copy.put("id", seq++);
                out.add(copy);
            }
        }
        return out;
    }

    private void ensureMinimumRealNewsWindow(List<Map<String, Object>> items, int realNewsCount) {
        if (realNewsCount <= 0 || items.size() <= MIN_REAL_NEWS_COUNT) {
            return;
        }

        int requiredReal = Math.min(MIN_REAL_NEWS_COUNT, realNewsCount);
        List<Map<String, Object>> realItems = new ArrayList<>();
        List<Map<String, Object>> syntheticItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            if (Boolean.TRUE.equals(item.get("synthetic"))) {
                syntheticItems.add(item);
            } else {
                realItems.add(item);
            }
        }

        if (realItems.size() >= requiredReal) {
            List<Map<String, Object>> reordered = new ArrayList<>(items.size());
            reordered.addAll(realItems);
            reordered.addAll(syntheticItems);
            items.clear();
            items.addAll(reordered);
        }
    }

    private void sortNewsListByPublishedAtDesc(List<Map<String, Object>> items) {
        items.sort((left, right) -> {
            long leftEpoch = longValue(left.get("publishedAtEpoch"));
            long rightEpoch = longValue(right.get("publishedAtEpoch"));
            int compare = Long.compare(rightEpoch, leftEpoch);
            if (compare != 0) {
                return compare;
            }

            boolean leftSynthetic = Boolean.TRUE.equals(left.get("synthetic"));
            boolean rightSynthetic = Boolean.TRUE.equals(right.get("synthetic"));
            if (leftSynthetic != rightSynthetic) {
                return leftSynthetic ? 1 : -1;
            }
            return 0;
        });
    }

    private void resequenceNewsList(List<Map<String, Object>> items) {
        for (int index = 0; index < items.size(); index++) {
            items.get(index).put("id", index + 1);
        }
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignore) {
                return 0L;
            }
        }
        return 0L;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Math.round(Double.parseDouble(text.trim()));
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return 0;
    }

    private List<Map<String, Object>> regroupByDate(List<Map<String, Object>> slice, Map<String, Object> fullPayload) {
        LinkedHashMap<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> item : slice) {
            String date = asText(item.get("publishedDate"));
            if (date.isBlank()) continue;
            Map<String, Object> group = groups.computeIfAbsent(date, d -> {
                Map<String, Object> g = new LinkedHashMap<>();
                g.put("date", d);
                g.put("label", asText(item.get("dayLabel")));
                g.put("items", new ArrayList<Map<String, Object>>());
                return g;
            });
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("items");
            items.add(item);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : groups.values()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
            g.put("count", items.size());
            out.add(g);
        }
        return out;
    }

    private String dayLabel(long daysAgo) {
        if (daysAgo <= 0) return "今天";
        if (daysAgo == 1) return "昨天";
        if (daysAgo == 2) return "前天";
        return daysAgo + " 天前";
    }

    private List<Map<String, Object>> synthesizeDayEntries(
            String city,
            String dateKey,
            int count,
            List<Map<String, Object>> existing,
            boolean allowLlmSynthesis
    ) {
        // Try LLM first — one call per sparse day, results cached on city+date.
        String cacheKey = city + "@" + dateKey;
        List<Map<String, Object>> cached = synthesizedDayCache.get(cacheKey);
        if (cached != null) {
            return trimSynthesizedList(cached, count);
        }

        List<Map<String, Object>> generated = new ArrayList<>();
        LocalDate date = LocalDate.parse(dateKey);
        long daysAgo = ChronoUnit.DAYS.between(date, LocalDate.now(SHANGHAI_ZONE));
        String label = dayLabel(daysAgo);

        List<String> existingTitles = new ArrayList<>();
        for (Map<String, Object> item : existing) existingTitles.add(asText(item.get("title")));

        if (allowLlmSynthesis) {
            try {
                llmService.generateNewsDigest(city + "（" + label + "，" + dateKey + "）", Math.max(count, 2), existingTitles)
                        .ifPresent(payload -> {
                            Object raw = payload.get("items");
                            if (raw instanceof List<?> rawList) {
                                for (Object node : rawList) {
                                    if (!(node instanceof Map<?, ?> m)) continue;
                                    String category = mapCategory(asText(m.get("category")));
                                    String title = trimText(asText(m.get("title")), 48);
                                    String summary = trimText(asText(m.get("summary")), 110);
                                    String content = trimText(asText(m.get("content")), 360);
                                    if (title.isBlank() && summary.isBlank()) continue;
                                    Map<String, Object> item = newsItem(
                                            0,
                                            category,
                                            title.isBlank() ? summary : title,
                                            summary,
                                            label + " · " + dateKey.substring(5),
                                            city + " AI 资讯整理",
                                            content.isBlank() ? summary : content,
                                            buildSpokenText(category, title, summary),
                                            ""
                                    );
                                    item.put("publishedDate", dateKey);
                                    item.put("dayLabel", label);
                                    item.put("synthetic", true);
                                    item.put("publishedAtEpoch", 0L);
                                    generated.add(item);
                                }
                            }
                        });
            } catch (Exception error) {
                // fall through to deterministic fallback
            }
        }

        if (generated.isEmpty()) {
            // Deterministic local fallback for this date.
            String[] seeds = new String[]{
                    "出行提醒|出行前可先确认路口、斑马线和无障碍电梯状态。",
                    "社区资讯|社区志愿服务或陪同活动通常在工作日下午较多，可提前预约。",
                    "民生快讯|优先留意医院、商场和社区窗口的辅助服务时间变化，减少现场等待。"
            };
            int start = (int) Math.abs((long) (city + dateKey).hashCode() % seeds.length);
            for (int i = 0; i < count; i++) {
                String[] parts = seeds[(start + i) % seeds.length].split("\\|", 2);
                String category = parts[0];
                String summary = parts[1];
                Map<String, Object> item = newsItem(
                        0,
                        category,
                        city + " " + category + "（" + label + "）",
                        summary,
                        label,
                        city + " 本地参考",
                        summary,
                        buildSpokenText(category, city + " " + category, summary),
                        ""
                );
                item.put("publishedDate", dateKey);
                item.put("dayLabel", label);
                item.put("synthetic", true);
                item.put("publishedAtEpoch", 0L);
                generated.add(item);
            }
        }

        synthesizedDayCache.put(cacheKey, generated);
        return trimSynthesizedList(generated, count);
    }

    private List<Map<String, Object>> trimSynthesizedList(List<Map<String, Object>> list, int count) {
        if (list.size() <= count) return new ArrayList<>(list);
        return new ArrayList<>(list.subList(0, count));
    }

    private List<RankedArticle> rankArticles(String city, OffsetDateTime now, List<NewsSourceClient.NewsArticle> articles) {
        List<RankedArticle> ranked = new ArrayList<>();
        Set<String> dedupeKeys = new HashSet<>();

        for (NewsSourceClient.NewsArticle article : articles) {
            String key = dedupeKey(article);
            if (!dedupeKeys.add(key)) {
                continue;
            }
            double score = score(city, now, article);
            if (score < MIN_LIVE_ARTICLE_SCORE) {
                continue;
            }
            ranked.add(new RankedArticle(article, score));
        }

        ranked.sort(Comparator.comparingDouble(RankedArticle::score).reversed());
        return ranked;
    }

    private double score(String city, OffsetDateTime now, NewsSourceClient.NewsArticle article) {
        double score = 0;
        String title = nonBlank(article.title(), "");
        String summary = nonBlank(article.description(), "");
        String content = nonBlank(article.content(), "");
        String source = nonBlank(article.source(), "");
        String url = nonBlank(article.url(), "");
        String haystack = (title + " " + summary + " " + content).toLowerCase();
        String cityToken = city.toLowerCase();
        if (haystack.contains(cityToken)) {
            score += 4.2;
        }

        if (containsAny(haystack, "无障碍", "盲道", "陪诊", "陪购", "志愿", "视障")) {
            score += 3.2;
        }
        if (containsAny(haystack, "交通", "地铁", "公交", "路口", "施工", "绕行", "电梯")) {
            score += 2.6;
        }
        if (containsAny(haystack, "公共服务", "社区", "医院", "便民", "活动", "发布", "开通", "优化", "启动", "发展", "消费", "创新")) {
            score += 1.8;
        }

        score += sourceAuthorityBonus(source, url);
        score += titleQualityBonus(title, summary);
        score -= lowValuePenalty(city, title, summary, content, source, url);

        OffsetDateTime publishedAt = article.publishedAt();
        if (publishedAt != null) {
            long hours = Math.abs(ChronoUnit.HOURS.between(publishedAt, now));
            if (hours <= 6) {
                score += 3.0;
            } else if (hours <= 24) {
                score += 2.0;
            } else if (hours <= 72) {
                score += 1.0;
            } else {
                score -= 0.8;
            }
        }

        int hour = now.atZoneSameInstant(ZoneId.systemDefault()).getHour();
        if (hour < 11 && "travel".equals(article.bucket())) {
            score += 2.2;
        } else if (hour < 18 && "service".equals(article.bucket())) {
            score += 1.8;
        } else if (hour >= 18 && "environment".equals(article.bucket())) {
            score += 1.8;
        }

        return score;
    }

    private double sourceAuthorityBonus(String source, String url) {
        String sourceLower = source.toLowerCase();
        String urlLower = url.toLowerCase();
        if (containsAny(sourceLower,
                "央视网", "人民网", "新华网", "光明网", "央广网", "中国新闻网",
                "解放日报", "文汇报", "新民晚报", "澎湃", "上观", "东方网", "上海发布", "青年报")
                || containsAny(urlLower,
                "cctv.com", "people.com.cn", "xinhuanet.com", "news.cn", "gmw.cn", "cnr.cn",
                "chinanews.com.cn", "thepaper.cn", "jfdaily.com", "eastday.com")) {
            return 2.2;
        }
        if (containsAny(sourceLower, "新华社", "人民日报", "央视", "中国政府网")) {
            return 2.0;
        }
        return 0;
    }

    private double titleQualityBonus(String title, String summary) {
        double bonus = 0;
        String normalizedTitle = normalizeText(title);
        if (normalizedTitle.length() >= 12) {
            bonus += 0.8;
        } else if (normalizedTitle.length() >= 8) {
            bonus += 0.4;
        }
        if (summary != null && summary.trim().length() >= 36) {
            bonus += 0.6;
        }
        if (containsAny((title + " " + summary).toLowerCase(),
                "发布", "启动", "开通", "落地", "整治", "提升", "优化", "建设", "保障", "活动", "服务")) {
            bonus += 0.8;
        }
        return bonus;
    }

    private double lowValuePenalty(String city, String title, String summary, String content, String source, String url) {
        double penalty = 0;
        String haystack = (title + " " + summary + " " + content + " " + source).toLowerCase();
        String normalizedTitle = normalizeText(title);

        if (normalizedTitle.length() < 4) {
            penalty += 5.0;
        }
        if (summary == null || summary.trim().length() < 16) {
            penalty += 1.5;
        }
        if (containsAny(haystack,
                "热点报道", "城事资讯", "新闻中心", "地方频道", "滚动新闻", "专题页",
                "板块资金流向", "资金流向", "主力资金", "个股", "行情", "涨停", "跌停", "股市", "证券", "基金")) {
            penalty += 6.0;
        }
        if (title != null && (title.contains("_") || title.contains("|"))) {
            penalty += 3.0;
        }
        if (looksLikeRegionEnumeration(summary) && normalizedTitle.length() <= normalizeText(city).length() + 2) {
            penalty += 5.0;
        }
        if (containsAny(url.toLowerCase(), "eastmoney", "10jqka", "stock", "finance.sina", "jrj.com", "cnstock")) {
            penalty += 4.0;
        }
        return penalty;
    }

    private boolean looksLikeRegionEnumeration(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        int hits = 0;
        String[] regions = {
                "北京", "天津", "河北", "山西", "内蒙古", "辽宁", "吉林", "黑龙江",
                "上海", "江苏", "浙江", "安徽", "福建", "江西", "山东", "河南",
                "湖北", "湖南", "广东", "广西", "海南", "重庆", "四川", "贵州",
                "云南", "西藏", "陕西", "甘肃", "青海", "宁夏", "新疆"
        };
        for (String region : regions) {
            if (summary.contains(region)) {
                hits++;
                if (hits >= 6) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String dedupeKey(NewsSourceClient.NewsArticle article) {
        String title = normalizeText(article.title());
        if (!title.isBlank()) {
            return title;
        }
        String url = normalizeText(article.url());
        if (!url.isBlank()) {
            return url;
        }
        return normalizeText(article.description());
    }

    private String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", "")
                .replaceAll("[，。、“”‘’：:；;,.!！?？()（）\\-_/]", "")
                .trim()
                .toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildAiSupplementNews(
            String city,
            List<Map<String, Object>> existingItems,
            int missingCount,
            Set<String> usedTitles
    ) {
        if (missingCount <= 0 || !llmService.isConfigured()) {
            return List.of();
        }

        List<String> existingTitles = existingItems.stream()
                .map(item -> String.valueOf(item.getOrDefault("title", "")))
                .filter(title -> !title.isBlank())
                .toList();

        Map<String, Object> payload = llmService.generateNewsDigest(city, missingCount, existingTitles).orElse(null);
        if (payload == null) {
            return List.of();
        }

        Object rawItems = payload.get("items");
        if (!(rawItems instanceof List<?> rawList)) {
            return List.of();
        }

        List<Map<String, Object>> generated = new ArrayList<>();
        int nextId = existingItems.size() + 1;
        for (Object rawItem : rawList) {
            if (!(rawItem instanceof Map<?, ?> rawMap)) {
                continue;
            }

            String category = normalizeCategory(asText(rawMap.get("category")));
            String title = trimText(nonBlank(asText(rawMap.get("title")), city + " 辅助资讯提醒"), 28);
            String summary = trimText(nonBlank(asText(rawMap.get("summary")), "系统根据城市和时段补充生成了一条适合直接播报的辅助提醒。"), 56);
            String content = trimText(nonBlank(asText(rawMap.get("content")), summary), 120);
            String spokenText = trimText(nonBlank(asText(rawMap.get("spokenText")), buildSpokenText(category, title, summary)), 90);

            String normalizedTitle = normalizeText(title);
            if (normalizedTitle.isBlank() || !usedTitles.add(normalizedTitle)) {
                continue;
            }

            generated.add(newsItem(
                    nextId++,
                    category,
                    title,
                    summary,
                    "刚刚生成",
                    "AI 城市资讯补全",
                    content,
                    spokenText
            ));
            generated.get(generated.size() - 1).put("publishedAtEpoch", 0L);
            generated.get(generated.size() - 1).put("synthetic", true);

            if (generated.size() >= missingCount) {
                break;
            }
        }
        return generated;
    }

    private List<Map<String, Object>> buildFallbackSupplement(
            String city,
            OffsetDateTime now,
            int startId,
            int missingCount,
            Set<String> usedTitles
    ) {
        List<Map<String, Object>> templates = fallbackNewsTemplates(city, now);
        List<Map<String, Object>> items = new ArrayList<>();
        int nextId = startId;

        for (Map<String, Object> template : templates) {
            String title = String.valueOf(template.getOrDefault("title", ""));
            String normalizedTitle = normalizeText(title);
            if (normalizedTitle.isBlank() || !usedTitles.add(normalizedTitle)) {
                continue;
            }

            items.add(newsItem(
                    nextId++,
                    String.valueOf(template.get("category")),
                    title,
                    String.valueOf(template.get("summary")),
                    String.valueOf(template.get("time")),
                    String.valueOf(template.get("source")),
                    String.valueOf(template.get("content")),
                    String.valueOf(template.get("spokenText"))
            ));
            items.get(items.size() - 1).put("publishedAtEpoch", 0L);
            items.get(items.size() - 1).put("synthetic", true);

            if (items.size() >= missingCount) {
                break;
            }
        }
        return items;
    }

    private List<Map<String, Object>> fallbackNewsTemplates(String city, OffsetDateTime now) {
        String partOfDay = switch (now.atZoneSameInstant(ZoneId.systemDefault()).getHour()) {
            case 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 -> "早间";
            case 11, 12, 13, 14, 15, 16, 17 -> "白天";
            default -> "晚间";
        };

        return List.of(
                newsItem(0, "出行提醒", city + " 重点路口与施工绕行提示",
                        "优先整理斑马线、施工围挡和地铁无障碍电梯等高频出行信息。", "今天 09:20",
                        city + " 出行服务", "系统会把本地出行和障碍信息改写成适合直接朗读的行动建议。",
                        city + "出行提醒已更新，请优先确认路口红绿灯、盲道连续性和施工绕行提示。"),
                newsItem(0, "社区资讯", city + " 陪同服务与志愿活动开放中",
                        "聚合陪诊、陪购、社区服务与无障碍活动，便于一次查看。", "今天 08:10",
                        city + " 社区中心", "当第三方新闻源不可用时，系统仍会保留城市级摘要和播报入口。",
                        city + "社区服务已更新，可查看陪诊、陪购和志愿活动的开放信息。"),
                newsItem(0, "民生快讯", city + " 医疗办事窗口开放时间提醒",
                        "优先确认医院导诊、便民窗口和社区服务点开放时段，减少白跑和排队。", "昨天 18:40",
                        city + " 民生资讯", "这部分内容用于补足视障用户高频办事场景里的窗口时间变化和辅助服务信息。",
                        city + "民生快讯已整理，建议出门前先确认医院导诊和社区窗口开放时间。"),
                newsItem(0, "出行提醒", city + " " + partOfDay + " 地铁首末班车与无障碍电梯提示",
                        "出门前可优先确认换乘站电梯、首末班车和临时封站信息，减少二次折返。", "刚刚",
                        city + " 交通辅助", "这类提醒用于补足公共交通换乘和无障碍设施可用性信息。",
                        city + partOfDay + "出行前，可先确认常用线路的电梯状态、首末班车和临时绕行信息。"),
                newsItem(0, "社区资讯", city + " 医院商场与社区便民服务关注点",
                        "可优先留意医院导诊、商场客服和社区窗口的辅助服务时间，减少现场等待。", "刚刚",
                        city + " 便民服务", "系统会把高频办事场景整理成更容易直接收听的辅助提示。",
                        city + "便民服务提醒已整理，可优先确认医院导诊、商场客服和社区窗口开放情况。"),
                newsItem(0, "民生快讯", city + " 夜间照明与公共设施检修提醒",
                        "若晚间外出，建议优先确认常用广场、地下通道和社区入口照明是否有临时检修。", "刚刚",
                        city + " 公共服务", "这类内容用于补足夜间照明和公共设施维护变化对出行的影响。",
                        city + "公共设施快讯已更新，晚间外出前可先确认常用路段照明和检修情况。")
        );
    }

    private String normalizeCategory(String raw) {
        String normalized = raw == null ? "" : raw.trim();
        return switch (normalized) {
            case "出行提醒", "travel" -> "出行提醒";
            case "社区资讯", "service" -> "社区资讯";
            case "民生快讯", "environment" -> "民生快讯";
            default -> "民生快讯";
        };
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String mapCategory(String bucket) {
        return switch (bucket) {
            case "travel" -> "出行提醒";
            case "service" -> "社区资讯";
            default -> "民生快讯";
        };
    }

    private String formatTime(OffsetDateTime publishedAt, OffsetDateTime now) {
        if (publishedAt == null) {
            return "刚刚";
        }

        long hours = Math.abs(ChronoUnit.HOURS.between(publishedAt, now));
        if (hours < 1) {
            return "刚刚";
        }
        if (hours < 24) {
            return hours + " 小时前";
        }
        long days = Math.abs(ChronoUnit.DAYS.between(publishedAt.toLocalDate(), now.toLocalDate()));
        if (days <= 1) {
            return "昨天";
        }
        return publishedAt.toLocalDate().toString();
    }

    private String buildSpokenText(String category, String title, String summary) {
        // Prefer reading only the content/summary; fall back to title only when summary is missing.
        String body = summary == null ? "" : summary.trim();
        if (body.isBlank()) {
            body = title == null ? "" : title.trim();
        }
        return trimText(body, 140);
    }

    private String nonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        return fallback;
    }

    private String nonBlank(String primary, String secondary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary.trim();
        }
        if (secondary != null && !secondary.isBlank()) {
            return secondary.trim();
        }
        return fallback;
    }

    private String trimText(String raw, int limit) {
        if (raw == null) {
            return "";
        }
        String normalized = raw.trim();
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, limit - 1) + "…";
    }

    private Map<String, Object> withLocationContext(Map<String, Object> payload, LocationResolver.ResolvedLocation resolvedLocation) {
        Map<String, Object> copied = copyPayload(payload);
        copied.put("locationContext", locationContext(resolvedLocation));
        return copied;
    }

    private Map<String, Object> locationContext(LocationResolver.ResolvedLocation resolvedLocation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("city", resolvedLocation.city());
        item.put("source", resolvedLocation.source());
        item.put("permission", resolvedLocation.permission());
        item.put("updatedAt", resolvedLocation.updatedAt());
        return item;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyPayload(Map<String, Object> payload) {
        Map<String, Object> copied = new LinkedHashMap<>(payload);
        copied.put("headline", new LinkedHashMap<>((Map<String, Object>) payload.get("headline")));
        copied.put("newsList", new ArrayList<>((List<Map<String, Object>>) payload.get("newsList")));
        if (payload.containsKey("quickEntries")) {
            copied.put("quickEntries", new ArrayList<>((List<Map<String, Object>>) payload.get("quickEntries")));
        }
        if (payload.containsKey("weather")) {
            copied.put("weather", new LinkedHashMap<>((Map<String, Object>) payload.get("weather")));
        }
        return copied;
    }

    private Map<String, Object> newsItem(
            int id,
            String category,
            String title,
            String summary,
            String time,
            String source,
            String content,
            String spokenText
    ) {
        return newsItem(id, category, title, summary, time, source, content, spokenText, "");
    }

    private Map<String, Object> newsItem(
            int id,
            String category,
            String title,
            String summary,
            String time,
            String source,
            String content,
            String spokenText,
            String url
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("category", category);
        item.put("title", title);
        item.put("summary", summary);
        item.put("time", time);
        item.put("source", source);
        item.put("content", content);
        item.put("spokenText", spokenText);
        item.put("url", url == null ? "" : url);
        return item;
    }

    private record CacheEntry(
            Map<String, Object> payload,
            OffsetDateTime expiresAt
    ) {
    }

    private record RankedArticle(
            NewsSourceClient.NewsArticle article,
            double score
    ) {
    }
}
