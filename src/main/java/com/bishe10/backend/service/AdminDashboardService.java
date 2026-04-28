package com.bishe10.backend.service;

import com.bishe10.backend.model.HistoryItem;
import com.bishe10.backend.repository.AdminDashboardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminDashboardService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminDashboardService.class);

    private final AdminDashboardRepository dashboardRepository;
    private final HistoryService historyService;

    public AdminDashboardService(AdminDashboardRepository dashboardRepository, HistoryService historyService) {
        this.dashboardRepository = dashboardRepository;
        this.historyService = historyService;
    }

    public Map<String, Object> overview() {
        Map<String, Object> databaseSummary = dashboardRepository.loadDatabaseSummary();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", OffsetDateTime.now().toString());
        payload.put("database", Map.of(
                "available", databaseSummary.getOrDefault("available", false),
                "message", databaseSummary.getOrDefault("message", "")
        ));
        payload.put("users", databaseSummary.getOrDefault("users", Map.of()));
        payload.put("admins", databaseSummary.getOrDefault("admins", Map.of()));
        payload.put("news", databaseSummary.getOrDefault("news", Map.of()));
        payload.put("feedback", databaseSummary.getOrDefault("feedback", Map.of()));
        payload.put("sources", databaseSummary.getOrDefault("sources", Map.of()));
        payload.put("personalization", databaseSummary.getOrDefault("personalization", Map.of()));
        payload.put("history", loadHistory());
        return payload;
    }

    private Map<String, Object> loadHistory() {
        Map<String, Object> history = new LinkedHashMap<>();
        try {
            List<HistoryItem> items = historyService.all();
            history.put("available", true);
            history.put("total", items.size());
            history.put("typeCounts", buildHistoryTypeCounts(items));
            history.put("latest", historyService.toPayload(historyService.latest(8)));
        } catch (RuntimeException error) {
            LOGGER.warn("admin dashboard: history summary unavailable", error);
            history.put("available", false);
            history.put("total", 0);
            history.put("typeCounts", List.of());
            history.put("latest", List.of());
        }
        return history;
    }

    private List<Map<String, Object>> buildHistoryTypeCounts(List<HistoryItem> items) {
        Map<String, Long> counts = items.stream()
                .collect(Collectors.groupingBy(
                        item -> normalizeHistoryType(item.type()),
                        LinkedHashMap::new,
                        Collectors.counting()
                ));
        return List.of(
                historyTypeRow("vision", "识图记录", counts),
                historyTypeRow("voice", "语音朗读", counts),
                historyTypeRow("news", "新闻记录", counts),
                historyTypeRow("weather", "天气查询", counts),
                historyTypeRow("generic", "其他记录", counts)
        );
    }

    private Map<String, Object> historyTypeRow(String key, String label, Map<String, Long> counts) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("value", counts.getOrDefault(key, 0L));
        return row;
    }

    private String normalizeHistoryType(String type) {
        if (type == null || type.isBlank()) {
            return "generic";
        }
        String normalized = type.trim().toLowerCase();
        return switch (normalized) {
            case "vision", "voice", "news", "weather" -> normalized;
            default -> "generic";
        };
    }
}
