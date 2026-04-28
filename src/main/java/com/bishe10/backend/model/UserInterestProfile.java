package com.bishe10.backend.model;

import java.time.OffsetDateTime;

public record UserInterestProfile(
        long id,
        String userId,
        String interestType,
        String interestValue,
        double weight,
        int positiveCount,
        int negativeCount,
        OffsetDateTime updatedAt
) {
}
