package com.bishe10.backend.service;

import com.bishe10.backend.model.NewsArticle;
import com.bishe10.backend.model.UserBlockRule;
import com.bishe10.backend.model.UserInterestProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PersonalizationService {

    private static final Set<String> NEGATIVE_ACTIONS = Set.of("DISLIKE", "SKIP", "NOT_INTERESTED", "BLOCK_SIMILAR");

    public boolean shouldFilter(NewsArticle article, Set<String> userActions, List<UserBlockRule> blockRules) {
        if (userActions != null && userActions.stream().anyMatch(NEGATIVE_ACTIONS::contains)) {
            return true;
        }
        if (blockRules == null || blockRules.isEmpty()) {
            return false;
        }
        for (UserBlockRule rule : blockRules) {
            if ("TAG".equals(rule.ruleType()) && containsIgnoreCase(article.tags(), rule.ruleValue())) {
                return true;
            }
            if ("CATEGORY".equals(rule.ruleType()) && equalsIgnoreCase(article.category(), rule.ruleValue())) {
                return true;
            }
            if ("SOURCE".equals(rule.ruleType()) && equalsIgnoreCase(article.source(), rule.ruleValue())) {
                return true;
            }
        }
        return false;
    }

    public ScoreResult score(NewsArticle article, List<UserInterestProfile> interests, Set<String> userActions) {
        if (interests == null || interests.isEmpty()) {
            return new ScoreResult(explorationScore(article, Set.of()), List.of("你还没有足够的反馈，系统将优先使用城市和时间规则"));
        }

        Map<String, UserInterestProfile> index = interests.stream()
                .collect(Collectors.toMap(
                        item -> key(item.interestType(), item.interestValue()),
                        Function.identity(),
                        (left, right) -> left
                ));

        List<String> reasons = new ArrayList<>();
        double tagScore = 0;
        for (String tag : article.tags()) {
            UserInterestProfile profile = index.get(key("TAG", tag));
            if (profile != null) {
                tagScore += profile.weight() * 5;
                if (profile.weight() > 0.2 && reasons.size() < 2) {
                    reasons.add("你最近喜欢过" + tag + "相关内容");
                }
            }
        }
        tagScore = clamp(tagScore, -15, 15);

        double categoryScore = 0;
        UserInterestProfile category = index.get(key("CATEGORY", article.category()));
        if (category != null) {
            categoryScore = clamp(category.weight() * 4, -8, 8);
            if (category.weight() > 0.2 && reasons.size() < 2) {
                reasons.add("你对" + article.category() + "类内容反馈较好");
            }
        }

        double sourceScore = 0;
        UserInterestProfile source = index.get(key("SOURCE", article.source()));
        if (source != null) {
            sourceScore = clamp(source.weight() * 3, -7, 7);
            if (source.weight() > 0.2 && reasons.size() < 2) {
                reasons.add("你对该来源的内容反馈较好");
            }
        }

        Set<String> knownCategories = interests.stream()
                .filter(item -> "CATEGORY".equals(item.interestType()))
                .map(UserInterestProfile::interestValue)
                .collect(Collectors.toSet());
        double exploration = explorationScore(article, knownCategories);
        return new ScoreResult(tagScore + categoryScore + sourceScore + exploration, reasons);
    }

    public double scopeBonus(String fetchScope) {
        return switch (fetchScope == null ? "" : fetchScope) {
            case "CITY" -> 3;
            case "INTEREST" -> 5;
            case "PROVINCE" -> 1;
            case "FALLBACK" -> -5;
            default -> 0;
        };
    }

    private double explorationScore(NewsArticle article, Set<String> knownCategories) {
        double score = 0;
        if (article.category() != null && !knownCategories.contains(article.category())) {
            score += 2;
        }
        if ("NATIONAL".equals(article.fetchScope()) || "PROVINCE".equals(article.fetchScope())) {
            score += 1.5;
        }
        return Math.min(5, score);
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        return values.stream().anyMatch(value -> equalsIgnoreCase(value, target));
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    private String key(String type, String value) {
        return (type == null ? "" : type.trim().toUpperCase(Locale.ROOT))
                + ":"
                + (value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record ScoreResult(double score, List<String> reasons) {
    }
}
