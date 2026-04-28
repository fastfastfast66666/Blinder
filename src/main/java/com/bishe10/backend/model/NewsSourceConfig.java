package com.bishe10.backend.model;

import java.time.OffsetDateTime;

public record NewsSourceConfig(
        String sourceKey,
        String sourceName,
        String sourceType,
        String endpoint,
        boolean enabled,
        int priority,
        String description,
        OffsetDateTime lastFetchAt,
        String lastStatus,
        String lastMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
