package com.bishe10.backend.model;

public record HistoryItem(
        long id,
        String type,
        String title,
        String summary,
        String time,
        String source,
        String spokenText
) {
}
