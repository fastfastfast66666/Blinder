package com.bishe10.backend.dto;

import java.util.List;

public record NewsSimplificationRequest(
        String city,
        List<Item> items
) {
    public record Item(
            String id,
            String title,
            String summary,
            String content,
            String source,
            String category
    ) {
    }
}
