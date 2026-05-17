package com.bishe10.backend.controller;

import com.bishe10.backend.service.NewsService;
import com.bishe10.backend.service.PersonalizedNewsService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class NewsController {

    private final NewsService newsService;
    private final PersonalizedNewsService personalizedNewsService;

    public NewsController(NewsService newsService, PersonalizedNewsService personalizedNewsService) {
        this.newsService = newsService;
        this.personalizedNewsService = personalizedNewsService;
    }

    @GetMapping("/api/news/recommendations")
    public Map<String, Object> recommendations(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) Boolean force
    ) {
        return ApiResponse.ok(newsService.buildRecommendations(city, lat, lng, page, pageSize, Boolean.TRUE.equals(force)));
    }

    @GetMapping("/api/news/recommend")
    public Map<String, Object> recommend(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String province,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Boolean force,
            @RequestParam(required = false) Boolean cacheOnly
    ) {
        return ApiResponse.ok(personalizedNewsService.recommend(
                userId,
                city,
                province,
                lat,
                lng,
                size,
                cursor,
                Boolean.TRUE.equals(force),
                Boolean.TRUE.equals(cacheOnly)
        ));
    }

    @PostMapping("/api/news/interpret")
    public Map<String, Object> interpret(@RequestBody Map<String, Object> request) {
        String title = request.get("title") instanceof String t ? t : "";
        String summary = request.get("summary") instanceof String s ? s : "";
        String content = request.get("content") instanceof String c ? c : "";
        String source = request.get("source") instanceof String src ? src : "";
        String category = request.get("category") instanceof String ct ? ct : "";
        return ApiResponse.ok(newsService.interpretArticle(title, summary, content, source, category));
    }

    @PostMapping("/api/news/{articleId}/feedback")
    public Map<String, Object> feedback(@PathVariable String articleId, @RequestBody Map<String, Object> request) {
        String userId = request.get("userId") instanceof String value ? value : "";
        String action = request.get("action") instanceof String value ? value : "";
        return ApiResponse.ok(personalizedNewsService.saveFeedback(articleId, userId, action));
    }
}
