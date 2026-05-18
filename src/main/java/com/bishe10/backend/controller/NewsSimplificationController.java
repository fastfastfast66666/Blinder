package com.bishe10.backend.controller;

import com.bishe10.backend.dto.NewsSimplificationRequest;
import com.bishe10.backend.service.NewsSimplificationService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class NewsSimplificationController {

    private final NewsSimplificationService newsSimplificationService;

    public NewsSimplificationController(NewsSimplificationService newsSimplificationService) {
        this.newsSimplificationService = newsSimplificationService;
    }

    @PostMapping("/api/news/simplify")
    public Map<String, Object> simplify(@RequestBody(required = false) NewsSimplificationRequest request) {
        return ApiResponse.ok(newsSimplificationService.simplify(request));
    }
}
