package com.bishe10.backend.service;

import com.bishe10.backend.model.NewsSourceConfig;
import com.bishe10.backend.repository.NewsSourceConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class NewsSourceConfigService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewsSourceConfigService.class);

    private final NewsSourceConfigRepository repository;

    public NewsSourceConfigService(NewsSourceConfigRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void initialize() {
        try {
            repository.initializeDefaults();
        } catch (Exception error) {
            LOGGER.warn("initialize news source config failed, will use in-memory defaults if needed", error);
        }
    }

    public Map<String, Object> listSources() {
        List<NewsSourceConfig> items;
        try {
            repository.initializeDefaults();
            items = repository.findAll();
        } catch (SQLException error) {
            LOGGER.warn("load news source config failed", error);
            items = repository.defaultConfigs();
        }
        long enabledCount = items.stream().filter(NewsSourceConfig::enabled).count();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items.stream().map(this::toPayload).toList());
        payload.put("total", items.size());
        payload.put("enabledCount", enabledCount);
        return payload;
    }

    public Map<String, Object> updateSource(String sourceKey, Map<String, Object> body) {
        Boolean enabled = body.containsKey("enabled") ? asBoolean(body.get("enabled")) : null;
        Integer priority = body.containsKey("priority") ? asInteger(body.get("priority")) : null;
        String description = body.containsKey("description") ? asString(body.get("description")) : null;
        try {
            NewsSourceConfig updated = repository.update(sourceKey, enabled, priority, description);
            return toPayload(updated);
        } catch (SQLException error) {
            LOGGER.warn("update news source config failed sourceKey={}", sourceKey, error);
            throw new IllegalStateException("新闻源配置保存失败，请确认数据库连接正常。");
        }
    }

    public Map<String, Object> resetDefaults() {
        try {
            repository.resetToDefaults();
        } catch (SQLException error) {
            LOGGER.warn("reset news source defaults failed", error);
            throw new IllegalStateException("新闻源默认配置初始化失败。");
        }
        return listSources();
    }

    private Map<String, Object> toPayload(NewsSourceConfig source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sourceKey", source.sourceKey());
        payload.put("sourceName", source.sourceName());
        payload.put("sourceType", source.sourceType());
        payload.put("endpoint", source.endpoint());
        payload.put("enabled", source.enabled());
        payload.put("priority", source.priority());
        payload.put("description", source.description());
        payload.put("lastFetchAt", source.lastFetchAt() == null ? "" : source.lastFetchAt().toString());
        payload.put("lastStatus", source.lastStatus() == null ? "" : source.lastStatus());
        payload.put("lastMessage", source.lastMessage() == null ? "" : source.lastMessage());
        payload.put("updatedAt", source.updatedAt() == null ? "" : source.updatedAt().toString());
        return payload;
    }

    private Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = asString(value);
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "enabled".equalsIgnoreCase(text);
    }

    private Integer asInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = asString(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("优先级必须是数字。");
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
