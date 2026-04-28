package com.bishe10.backend.service;

import com.bishe10.backend.dto.FeedbackResponse;
import com.bishe10.backend.dto.UserNewsProfileResponse;
import com.bishe10.backend.model.NewsArticle;
import com.bishe10.backend.model.UserBlockRule;
import com.bishe10.backend.model.UserInterestProfile;
import com.bishe10.backend.repository.UserBlockRuleRepository;
import com.bishe10.backend.repository.UserInterestProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Service
public class UserProfileService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfileService.class);

    private final UserInterestProfileRepository interestRepository;
    private final UserBlockRuleRepository blockRuleRepository;

    public UserProfileService(UserInterestProfileRepository interestRepository, UserBlockRuleRepository blockRuleRepository) {
        this.interestRepository = interestRepository;
        this.blockRuleRepository = blockRuleRepository;
    }

    public List<FeedbackResponse.UpdatedProfile> updateProfileByFeedback(String userId, NewsArticle article, String action) {
        List<FeedbackResponse.UpdatedProfile> updated = new ArrayList<>();
        Delta delta = delta(action);
        if (delta == null) {
            return updated;
        }

        int positive = delta.positive() ? 1 : 0;
        int negative = delta.negative() ? 1 : 0;

        try {
            for (String tag : article.tags()) {
                if (isBlank(tag)) continue;
                UserInterestProfile profile = interestRepository.applyDelta(userId, "TAG", tag, delta.tagDelta(), positive, negative);
                updated.add(new FeedbackResponse.UpdatedProfile("TAG", tag, profile.weight()));
                if ("BLOCK_SIMILAR".equals(action)) {
                    blockRuleRepository.save(userId, "TAG", tag);
                }
            }
            if (!isBlank(article.category())) {
                UserInterestProfile profile = interestRepository.applyDelta(userId, "CATEGORY", article.category(), delta.categoryDelta(), positive, negative);
                updated.add(new FeedbackResponse.UpdatedProfile("CATEGORY", article.category(), profile.weight()));
                if ("BLOCK_SIMILAR".equals(action)) {
                    blockRuleRepository.save(userId, "CATEGORY", article.category());
                }
            }
            if (!isBlank(article.source()) && delta.sourceDelta() != 0) {
                UserInterestProfile profile = interestRepository.applyDelta(userId, "SOURCE", article.source(), delta.sourceDelta(), positive, negative);
                updated.add(new FeedbackResponse.UpdatedProfile("SOURCE", article.source(), profile.weight()));
                if ("BLOCK_SIMILAR".equals(action)) {
                    blockRuleRepository.save(userId, "SOURCE", article.source());
                }
            }
            if (!isBlank(article.city()) && delta.positive()) {
                UserInterestProfile profile = interestRepository.applyDelta(userId, "CITY", article.city(), 0.1, 1, 0);
                updated.add(new FeedbackResponse.UpdatedProfile("CITY", article.city(), profile.weight()));
            }
        } catch (SQLException error) {
            LOGGER.warn("update user news profile failed userId={} articleId={}", userId, article.articleId(), error);
        }

        return updated;
    }

    public List<UserInterestProfile> interests(String userId) {
        try {
            return interestRepository.findByUser(userId);
        } catch (SQLException error) {
            LOGGER.warn("load user interests failed userId={}", userId, error);
            return List.of();
        }
    }

    public List<UserBlockRule> blockRules(String userId) {
        try {
            return blockRuleRepository.findByUser(userId);
        } catch (SQLException error) {
            LOGGER.warn("load user block rules failed userId={}", userId, error);
            return List.of();
        }
    }

    public UserNewsProfileResponse profile(String userId) {
        List<UserNewsProfileResponse.InterestItem> interests = interests(userId).stream()
                .map(item -> new UserNewsProfileResponse.InterestItem(
                        item.interestType(),
                        item.interestValue(),
                        item.weight(),
                        item.positiveCount(),
                        item.negativeCount()
                ))
                .toList();
        List<UserNewsProfileResponse.BlockRuleItem> blockRules = blockRules(userId).stream()
                .map(item -> new UserNewsProfileResponse.BlockRuleItem(item.ruleType(), item.ruleValue()))
                .toList();
        return new UserNewsProfileResponse(userId, interests, blockRules);
    }

    private Delta delta(String action) {
        return switch (action) {
            case "LIKE" -> new Delta(0.3, 0.2, 0.1, true, false);
            case "FAVORITE" -> new Delta(0.5, 0.3, 0.2, true, false);
            case "DISLIKE" -> new Delta(-0.4, -0.3, -0.1, false, true);
            case "SKIP" -> new Delta(-0.1, -0.05, 0, false, true);
            case "NOT_INTERESTED" -> new Delta(-0.6, -0.4, -0.1, false, true);
            case "BLOCK_SIMILAR" -> new Delta(-1.0, -0.8, -0.3, false, true);
            case "VIEW" -> null;
            default -> null;
        };
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Delta(
            double tagDelta,
            double categoryDelta,
            double sourceDelta,
            boolean positive,
            boolean negative
    ) {
    }
}
