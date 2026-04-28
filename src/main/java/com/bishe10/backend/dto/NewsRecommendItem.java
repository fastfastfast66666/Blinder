package com.bishe10.backend.dto;

import java.util.List;
import java.util.Map;

public record NewsRecommendItem(
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
        String publishTime,
        String time,
        double score,
        double baseScore,
        double personalScore,
        String fetchScope,
        List<String> recommendReasons,
        Map<String, Boolean> userActions,
        boolean synthetic
) {
}
