package com.bishe10.backend.model;

import java.time.OffsetDateTime;

public record UserBlockRule(
        long id,
        String userId,
        String ruleType,
        String ruleValue,
        OffsetDateTime createdAt
) {
}
