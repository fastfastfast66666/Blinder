package com.bishe10.backend.controller;

import com.bishe10.backend.service.AuthService;
import com.bishe10.backend.service.PersonalizedNewsService;
import com.bishe10.backend.support.ApiResponse;
import com.bishe10.backend.support.UnauthorizedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserNewsProfileController {

    private final PersonalizedNewsService personalizedNewsService;
    private final AuthService authService;

    public UserNewsProfileController(PersonalizedNewsService personalizedNewsService, AuthService authService) {
        this.personalizedNewsService = personalizedNewsService;
        this.authService = authService;
    }

    @GetMapping("/api/users/{userId}/news-profile")
    public Map<String, Object> profile(@PathVariable String userId) {
        return ApiResponse.ok(personalizedNewsService.profile(userId));
    }

    @PostMapping("/api/users/{userId}/news-profile/reset")
    public Map<String, Object> reset(
            @PathVariable String userId,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        String currentUserId = authService.resolveUserId(extractToken(authHeader))
                .orElseThrow(() -> new UnauthorizedException("\u8bf7\u5148\u767b\u5f55\u8d26\u53f7\u3002"));
        if (!currentUserId.equals(userId == null ? "" : userId.trim())) {
            throw new IllegalArgumentException("\u53ea\u80fd\u91cd\u7f6e\u5f53\u524d\u767b\u5f55\u8d26\u53f7\u7684\u63a8\u8350\u504f\u597d\u3002");
        }
        return ApiResponse.ok(personalizedNewsService.resetProfile(userId));
    }

    private String extractToken(String authHeader) {
        if (authHeader == null) return "";
        String trimmed = authHeader.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }
}
