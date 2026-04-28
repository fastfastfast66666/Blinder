package com.bishe10.backend.dto;

import java.util.List;
import java.util.Map;

public record NewsRecommendResponse(
        List<NewsRecommendItem> items,
        boolean hasMore,
        Map<String, Object> locationContext,
        Map<String, Object> weather,
        List<Map<String, Object>> quickEntries
) {
}
