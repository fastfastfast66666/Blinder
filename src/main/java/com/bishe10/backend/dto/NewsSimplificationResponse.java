package com.bishe10.backend.dto;

import java.util.List;

public record NewsSimplificationResponse(
        String city,
        String provider,
        String mode,
        int requested,
        List<Item> items
) {
    public record Item(
            String id,
            String title,
            String simplifiedText,
            String spokenText,
            String mode
    ) {
    }
}
