package com.bishe10.backend.controller;

import com.bishe10.backend.service.AuthService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/send-code")
    public Map<String, Object> sendCode(@RequestBody Map<String, Object> body) {
        String email = asString(body.get("email"));
        return ApiResponse.ok(authService.sendCode(email));
    }

    @PostMapping("/api/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body) {
        String email = asString(body.get("email"));
        String code = asString(body.get("code"));
        String password = asString(body.get("password"));
        String nickname = asString(body.get("nickname"));
        return ApiResponse.ok(authService.register(email, code, password, nickname));
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body) {
        String email = asString(body.get("email"));
        String password = asString(body.get("password"));
        return ApiResponse.ok(authService.loginWithPassword(email, password));
    }

    @PostMapping("/api/auth/login-code")
    public Map<String, Object> loginCode(@RequestBody Map<String, Object> body) {
        String email = asString(body.get("email"));
        String code = asString(body.get("code"));
        return ApiResponse.ok(authService.loginWithCode(email, code));
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(@RequestBody(required = false) Map<String, Object> body,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractToken(authHeader);
        if ((token == null || token.isBlank()) && body != null) {
            token = asString(body.get("token"));
        }
        authService.logout(token);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String token = extractToken(authHeader);
        return authService.resolveMe(token)
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.ok(Map.of("user", (Object) null, "authenticated", false)));
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
