package com.bishe10.backend.service;

import com.bishe10.backend.repository.SystemAuditLogRepository;
import com.bishe10.backend.repository.SystemAuditLogRepository.AuditActor;
import com.bishe10.backend.repository.SystemAuditLogRepository.AuditEvent;
import com.bishe10.backend.repository.SystemAuditLogRepository.Query;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SystemAuditLogService {

    private final SystemAuditLogRepository repository;

    public SystemAuditLogService(SystemAuditLogRepository repository) {
        this.repository = repository;
    }

    public AuditActor resolveActor(HttpServletRequest request) {
        return repository.resolveActor(extractToken(request.getHeader("Authorization")));
    }

    public void recordRequest(
            HttpServletRequest request,
            AuditActor actor,
            int statusCode,
            long durationMs,
            Throwable failure
    ) {
        String path = normalizedPath(request);
        AuditDescriptor descriptor = describe(request.getMethod(), path);
        boolean success = failure == null && statusCode < 400;
        String message = success ? "请求完成" : failureMessage(statusCode, failure);

        repository.save(new AuditEvent(
                actor.actorType(),
                actor.actorId(),
                actor.actorName(),
                descriptor.moduleKey(),
                descriptor.moduleName(),
                descriptor.actionKey(),
                descriptor.actionName(),
                descriptor.targetType(),
                descriptor.targetId(),
                clip(request.getMethod(), 16),
                clip(path, 512),
                clip(request.getQueryString(), 1024),
                statusCode,
                success,
                Math.max(0, durationMs),
                clip(clientIp(request), 64),
                clip(request.getHeader("User-Agent"), 512),
                clip(message, 512)
        ));
    }

    public Map<String, Object> listLogs(
            String keyword,
            String moduleKey,
            String actorType,
            String result,
            String startTime,
            String endTime,
            int pageNum,
            int pageSize
    ) {
        return repository.listLogs(new Query(
                safe(keyword),
                safe(moduleKey),
                safe(actorType),
                safe(result),
                safe(startTime),
                safe(endTime),
                pageNum,
                pageSize
        ));
    }

    private AuditDescriptor describe(String method, String path) {
        String normalizedMethod = method == null ? "" : method.toUpperCase();

        if (path.equals("/api/admin/auth/login")) {
            return descriptor("admin_auth", "管理员登录", "login", "管理员登录", "admin", "");
        }
        if (path.equals("/api/admin/auth/logout")) {
            return descriptor("admin_auth", "管理员登录", "logout", "管理员退出登录", "admin", "");
        }
        if (path.equals("/api/admin/auth/me")) {
            return descriptor("admin_auth", "管理员登录", "me", "查看当前管理员", "admin", "");
        }
        if (path.equals("/api/admin/dashboard")) {
            return descriptor("admin_dashboard", "数据总览", "view", "查看数据总览", "dashboard", "");
        }
        if (path.startsWith("/api/admin/audit-logs")) {
            return descriptor("admin_audit", "系统日志", "view", "查看系统日志", "auditLog", "");
        }
        if (path.startsWith("/api/admin/users")) {
            return adminCrudDescriptor(normalizedMethod, path, "admin_user", "用户管理", "user");
        }
        if (path.startsWith("/api/admin/news-sources/defaults")) {
            return descriptor("admin_news_source", "新闻源管理", "reset", "恢复默认新闻源", "newsSource", "");
        }
        if (path.startsWith("/api/admin/news-sources")) {
            return adminCrudDescriptor(normalizedMethod, path, "admin_news_source", "新闻源管理", "newsSource");
        }
        if (path.startsWith("/api/admin/news-algorithm")) {
            return descriptor("admin_algorithm", "新闻算法管理", "adjust", "调整新闻算法参数", "newsAlgorithm", "");
        }

        if (path.equals("/api/auth/send-code")) {
            return descriptor("auth", "用户登录注册", "send_code", "发送验证码", "verificationCode", "");
        }
        if (path.equals("/api/auth/register")) {
            return descriptor("auth", "用户登录注册", "register", "用户注册", "user", "");
        }
        if (path.equals("/api/auth/login") || path.equals("/api/auth/login-code")) {
            return descriptor("auth", "用户登录注册", "login", "用户登录", "user", "");
        }
        if (path.equals("/api/auth/logout")) {
            return descriptor("auth", "用户登录注册", "logout", "用户退出登录", "user", "");
        }
        if (path.equals("/api/auth/me")) {
            return descriptor("auth", "用户登录注册", "me", "查看当前用户", "user", "");
        }

        if (path.contains("/feedback") && path.startsWith("/api/news/")) {
            return descriptor("news_feedback", "新闻反馈", "feedback", "提交新闻反馈", "article", segment(path, 2));
        }
        if (path.startsWith("/api/news")) {
            return descriptor("news", "新闻资讯", "view", "获取新闻资讯", "news", "");
        }
        if (path.startsWith("/api/weather")) {
            return descriptor("weather", "天气服务", "view", "查询天气", "weather", "");
        }
        if (path.startsWith("/api/vision")) {
            return descriptor("vision", "识图服务", "analyze", "进行识图分析", "vision", "");
        }
        if (path.startsWith("/api/voice")) {
            return descriptor("voice", "语音服务", "synthesize", "生成语音播报", "voice", "");
        }
        if (path.startsWith("/api/history")) {
            String action = "POST".equals(normalizedMethod) ? "create" : "view";
            String actionName = "POST".equals(normalizedMethod) ? "新增历史记录" : "查看历史记录";
            return descriptor("history", "历史记录", action, actionName, "history", "");
        }
        if (path.contains("/news-profile")) {
            return descriptor("personalization", "个性化画像", "view", "查看新闻画像", "user", segment(path, 2));
        }
        return descriptor("system", "系统接口", "request", "访问系统接口", "api", "");
    }

    private AuditDescriptor adminCrudDescriptor(
            String method,
            String path,
            String moduleKey,
            String moduleName,
            String targetType
    ) {
        String targetId = lastSegment(path);
        return switch (method) {
            case "POST" -> descriptor(moduleKey, moduleName, "create", "新增" + moduleName.replace("管理", ""), targetType, targetId);
            case "PUT", "PATCH" -> descriptor(moduleKey, moduleName, "update", "更新" + moduleName.replace("管理", ""), targetType, targetId);
            case "DELETE" -> descriptor(moduleKey, moduleName, "delete", "删除" + moduleName.replace("管理", ""), targetType, targetId);
            default -> descriptor(moduleKey, moduleName, "view", "查看" + moduleName, targetType, "");
        };
    }

    private AuditDescriptor descriptor(
            String moduleKey,
            String moduleName,
            String actionKey,
            String actionName,
            String targetType,
            String targetId
    ) {
        return new AuditDescriptor(moduleKey, moduleName, actionKey, actionName, targetType, targetId);
    }

    private String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String extractToken(String authHeader) {
        if (authHeader == null) return "";
        String trimmed = authHeader.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private String failureMessage(int statusCode, Throwable failure) {
        if (failure != null && failure.getMessage() != null && !failure.getMessage().isBlank()) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        return "请求返回状态码 " + statusCode;
    }

    private String segment(String path, int index) {
        String[] parts = path.split("/");
        int seen = -1;
        for (String part : parts) {
            if (part.isBlank()) continue;
            seen++;
            if (seen == index) return part;
        }
        return "";
    }

    private String lastSegment(String path) {
        if (path == null || path.isBlank()) return "";
        int index = path.lastIndexOf('/');
        if (index < 0 || index >= path.length() - 1) return "";
        String value = path.substring(index + 1);
        return value.startsWith("api") || value.equals("users") || value.equals("news-sources") ? "" : value;
    }

    private String clip(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record AuditDescriptor(
            String moduleKey,
            String moduleName,
            String actionKey,
            String actionName,
            String targetType,
            String targetId
    ) {
    }
}
