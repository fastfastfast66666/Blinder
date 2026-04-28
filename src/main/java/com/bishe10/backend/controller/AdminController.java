package com.bishe10.backend.controller;

import com.bishe10.backend.service.AdminService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/api/admin/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String username = asString(body.get("username"));
        String password = asString(body.get("password"));
        return ApiResponse.ok(adminService.login(username, password));
    }

    @GetMapping("/api/admin/auth/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        return ApiResponse.ok(adminService.me(extractToken(authHeader)));
    }

    @PostMapping("/api/admin/auth/logout")
    public Map<String, Object> logout(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String token = extractToken(authHeader);
        if ((token == null || token.isBlank()) && body != null) {
            token = asString(body.get("token"));
        }
        adminService.logout(token);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/api/admin/users")
    public Map<String, Object> listUsers(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                         @RequestParam(value = "keyword", required = false) String keyword,
                                         @RequestParam(value = "status", required = false) String status,
                                         @RequestParam(value = "pageNum", defaultValue = "1") int pageNum,
                                         @RequestParam(value = "pageSize", defaultValue = "10") int pageSize) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(adminService.listUsers(keyword, status, pageNum, pageSize));
    }

    @PostMapping("/api/admin/users")
    public Map<String, Object> createUser(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                          @RequestBody Map<String, Object> body) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(adminService.createUser(body));
    }

    @PutMapping("/api/admin/users/{id}")
    public Map<String, Object> updateUser(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                          @PathVariable long id,
                                          @RequestBody Map<String, Object> body) {
        adminService.requireAdmin(extractToken(authHeader));
        return ApiResponse.ok(adminService.updateUser(id, body));
    }

    @DeleteMapping("/api/admin/users/{id}")
    public Map<String, Object> deleteUser(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                          @PathVariable long id) {
        adminService.requireAdmin(extractToken(authHeader));
        adminService.deleteUser(id);
        return ApiResponse.ok(Map.of("ok", true));
    }

    private String extractToken(String authHeader) {
        if (authHeader == null) return "";
        String trimmed = authHeader.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
