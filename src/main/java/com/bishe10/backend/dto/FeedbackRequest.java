package com.bishe10.backend.dto;

public record FeedbackRequest(
        String userId,
        String action
) {
}
