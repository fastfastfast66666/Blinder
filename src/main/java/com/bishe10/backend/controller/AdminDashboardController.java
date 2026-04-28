package com.bishe10.backend.controller;

import com.bishe10.backend.service.AdminDashboardService;
import com.bishe10.backend.service.AdminService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AdminDashboardController {

    private final AdminService adminService;
    private final AdminDashboardService dashboardService;

    public AdminDashboardController(AdminService adminService, AdminDashboardService dashboardService) {
        this.adminService = adminService;
        this.dashboardService = dashboardService;
    }

    @GetMapping("/api/admin/dashboard")
    public Map<String, Object> overview(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(dashboardService.overview());
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
