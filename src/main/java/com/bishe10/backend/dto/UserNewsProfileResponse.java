package com.bishe10.backend.dto;

import java.util.List;

public record UserNewsProfileResponse(
        String userId,
        List<InterestItem> interests,
        List<BlockRuleItem> blockRules
) {
    public record InterestItem(
            String type,
            String value,
            double weight,
            int positiveCount,
            int negativeCount
    ) {
    }

    public record BlockRuleItem(
            String type,
            String value
    ) {
    }
}
