package com.bishe10.backend.controller;

import com.bishe10.backend.service.AdminService;
import com.bishe10.backend.service.NewsSourceConfigService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AdminNewsSourceController {

    private final AdminService adminService;
    private final NewsSourceConfigService newsSourceConfigService;

    public AdminNewsSourceController(AdminService adminService, NewsSourceConfigService newsSourceConfigService) {
        this.adminService = adminService;
        this.newsSourceConfigService = newsSourceConfigService;
    }

    @GetMapping("/api/admin/news-sources")
    public Map<String, Object> listSources(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(newsSourceConfigService.listSources());
    }

    @PutMapping("/api/admin/news-sources/{sourceKey}")
    public Map<String, Object> updateSource(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                            @PathVariable String sourceKey,
                                            @RequestBody Map<String, Object> body) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(newsSourceConfigService.updateSource(sourceKey, body));
    }

    @PostMapping("/api/admin/news-sources/defaults")
    public Map<String, Object> resetDefaults(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(newsSourceConfigService.resetDefaults());
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
