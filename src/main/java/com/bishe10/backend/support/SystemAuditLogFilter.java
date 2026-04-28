package com.bishe10.backend.support;

import com.bishe10.backend.repository.SystemAuditLogRepository.AuditActor;
import com.bishe10.backend.service.SystemAuditLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class SystemAuditLogFilter extends OncePerRequestFilter {

    private final SystemAuditLogService auditLogService;

    public SystemAuditLogFilter(SystemAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = normalizedPath(request);
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !path.startsWith("/api/")
                || path.equals("/api/health")
                || path.equals("/api/meta")
                || path.startsWith("/api/audio/")
                || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        AuditActor actor = auditLogService.resolveActor(request);
        Throwable failure = null;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            int statusCode = response.getStatus();
            if (failure != null && statusCode < 400) {
                statusCode = 500;
            }
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;
            auditLogService.recordRequest(request, actor, statusCode, durationMs, failure);
        }
    }

    private String normalizedPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
