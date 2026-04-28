package com.bishe10.backend.model;

import java.time.OffsetDateTime;
import java.util.List;

public record NewsArticle(
        String articleId,
        String title,
        String summary,
        String content,
        String url,
        String source,
        String city,
        String province,
        String category,
        List<String> tags,
        OffsetDateTime publishTime,
        String fetchScope,
        String contentHash,
        boolean synthetic
) {
}
