package com.bishe10.backend.controller;

import com.bishe10.backend.service.PersonalizedNewsService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class UserNewsProfileController {

    private final PersonalizedNewsService personalizedNewsService;

    public UserNewsProfileController(PersonalizedNewsService personalizedNewsService) {
        this.personalizedNewsService = personalizedNewsService;
    }

    @GetMapping("/api/users/{userId}/news-profile")
    public Map<String, Object> profile(@PathVariable String userId) {
        return ApiResponse.ok(personalizedNewsService.profile(userId));
    }

    @PostMapping("/api/users/{userId}/news-profile/reset")
    public Map<String, Object> reset(@PathVariable String userId) {
        return ApiResponse.ok(personalizedNewsService.resetProfile(userId));
    }
}
