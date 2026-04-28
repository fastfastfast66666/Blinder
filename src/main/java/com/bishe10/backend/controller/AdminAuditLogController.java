package com.bishe10.backend.controller;

import com.bishe10.backend.service.AdminService;
import com.bishe10.backend.service.SystemAuditLogService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AdminAuditLogController {

    private final AdminService adminService;
    private final SystemAuditLogService auditLogService;

    public AdminAuditLogController(AdminService adminService, SystemAuditLogService auditLogService) {
        this.adminService = adminService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/api/admin/audit-logs")
    public Map<String, Object> listLogs(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "moduleKey", required = false) String moduleKey,
            @RequestParam(value = "actorType", required = false) String actorType,
            @RequestParam(value = "result", required = false) String result,
            @RequestParam(value = "startTime", required = false) String startTime,
            @RequestParam(value = "endTime", required = false) String endTime,
            @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
            @RequestParam(value = "pageSize", defaultValue = "10") int pageSize
    ) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(auditLogService.listLogs(
                keyword,
                moduleKey,
                actorType,
                result,
                startTime,
                endTime,
                pageNum,
                pageSize
        ));
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
