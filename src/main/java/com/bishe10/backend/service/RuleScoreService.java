package com.bishe10.backend.service;

import com.bishe10.backend.model.NewsArticle;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class RuleScoreService {

    public ScoreResult score(NewsArticle article, String city, String province, OffsetDateTime now) {
        double score = 0;
        List<String> reasons = new ArrayList<>();
        String haystack = (safe(article.title()) + " " + safe(article.summary()) + " " + safe(article.content()) + " " + String.join(" ", article.tags())).toLowerCase(Locale.ROOT);

        if (!isBlank(city) && (safe(article.city()).contains(city) || haystack.contains(city.toLowerCase(Locale.ROOT)))) {
            score += 25;
            reasons.add("与你所在城市相关");
        } else if (!isBlank(province) && (safe(article.province()).contains(province) || haystack.contains(province.toLowerCase(Locale.ROOT)))) {
            score += 14;
            reasons.add("与你所在省份相关");
        } else if ("NATIONAL".equals(article.fetchScope())) {
            score += 8;
        }

        if (containsAny(haystack, "交通", "地铁", "公交", "高铁", "火车", "路口", "施工", "绕行", "电梯", "出行")) {
            score += 15;
            reasons.add("包含交通出行相关信息");
        }
        if (containsAny(haystack, "无障碍", "盲道", "视障", "陪诊", "陪购", "助残", "志愿")) {
            score += 15;
            reasons.add("包含无障碍相关信息");
        }
        if (containsAny(haystack, "民生", "社区", "医院", "便民", "服务", "办事", "公共服务", "发布")) {
            score += 10;
            reasons.add("属于民生服务类新闻");
        }

        double authority = sourceAuthority(article.source(), article.url());
        score += authority;
        if (authority >= 10) {
            reasons.add("来自权威来源");
        }

        double freshness = freshness(article.publishTime(), now);
        score += freshness;
        if (freshness >= 10) {
            reasons.add("发布时间较近");
        }

        double timeFit = timeFit(article, now);
        score += timeFit;
        if (timeFit >= 3 && reasons.size() < 4) {
            reasons.add("符合当前时段的信息需求");
        }

        return new ScoreResult(Math.max(0, Math.min(100, score)), reasons);
    }

    private double sourceAuthority(String source, String url) {
        String text = (safe(source) + " " + safe(url)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "新华社", "人民网", "央视", "cctv.com", "people.com.cn", "xinhuanet.com", "news.cn", "gov.cn")) {
            return 15;
        }
        if (containsAny(text, "日报", "晚报", "发布", "thepaper.cn", "chinanews.com", "jfdaily.com", "eastday.com")) {
            return 11;
        }
        if (containsAny(text, "新闻", "资讯", "中心")) {
            return 7;
        }
        return 3;
    }

    private double freshness(OffsetDateTime publishTime, OffsetDateTime now) {
        if (publishTime == null) {
            return -4;
        }
        long hours = ChronoUnit.HOURS.between(publishTime, now);
        if (hours < 0) {
            hours = 0;
        }
        if (hours <= 6) return 20;
        if (hours <= 24) return 16;
        if (hours <= 72) return 10;
        if (hours <= 168) return 3;
        return -10;
    }

    private double timeFit(NewsArticle article, OffsetDateTime now) {
        int hour = now.getHour();
        String category = safe(article.category());
        if (hour < 11 && category.contains("交通")) {
            return 5;
        }
        if (hour >= 18 && (category.contains("交通") || category.contains("民生"))) {
            return 4;
        }
        if (hour >= 11 && hour < 18 && category.contains("民生")) {
            return 4;
        }
        return 1;
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

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ScoreResult(double score, List<String> reasons) {
    }
}
