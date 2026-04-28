package com.bishe10.backend.dto;

import java.util.List;

public record FeedbackResponse(
        boolean success,
        String message,
        List<UpdatedProfile> updatedProfile
) {
    public record UpdatedProfile(
            String type,
            String value,
            double weight
    ) {
    }
}
