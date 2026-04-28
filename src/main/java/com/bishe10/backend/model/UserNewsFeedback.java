package com.bishe10.backend.model;

import java.time.OffsetDateTime;

public record UserNewsFeedback(
        long id,
        String userId,
        String articleId,
        String action,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
