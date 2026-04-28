package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.model.HistoryItem;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class HistoryService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;
    private final Path historyFile;
    private final AtomicLong nextId = new AtomicLong(1);

    public HistoryService(ObjectMapper objectMapper, Bishe10Properties properties) {
        this.objectMapper = objectMapper;
        this.historyFile = Path.of(properties.getStorage().getRootDir(), "history.json");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(historyFile.getParent());
        if (Files.notExists(historyFile)) {
            Files.writeString(historyFile, "[]", StandardOpenOption.CREATE);
        }
        long maxId = readAll().stream()
                .map(HistoryItem::id)
                .max(Comparator.naturalOrder())
                .orElse(0L);
        nextId.set(maxId + 1);
    }

    public synchronized List<HistoryItem> latest(int limit) {
        return readAll().stream()
                .sorted(Comparator.comparingLong(HistoryItem::id).reversed())
                .limit(Math.max(limit, 0))
                .toList();
    }

    public synchronized List<HistoryItem> all() {
        return List.copyOf(readAll());
    }

    public synchronized HistoryItem append(String type, String title, String summary, String source, String spokenText) {
        HistoryItem item = new HistoryItem(
                nextId.getAndIncrement(),
                safe(type),
                safe(title),
                safe(summary),
                LocalDateTime.now().format(TIME_FORMATTER),
                safe(source),
                safe(spokenText)
        );

        List<HistoryItem> items = new ArrayList<>(readAll());
        items.add(item);
        writeAll(items);
        return item;
    }

    public synchronized HistoryItem append(Map<String, Object> input) {
        return append(
                asString(input.get("type"), "generic"),
                asString(input.get("title"), "未命名记录"),
                asString(input.get("summary"), ""),
                asString(input.get("source"), "用户操作"),
                asString(input.get("spokenText"), "")
        );
    }

    public synchronized int count() {
        return readAll().size();
    }

    public List<Map<String, Object>> toPayload(List<HistoryItem> items) {
        return items.stream().map(item -> {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", item.id());
            payload.put("type", item.type());
            payload.put("title", item.title());
            payload.put("summary", item.summary());
            payload.put("time", item.time());
            payload.put("source", item.source());
            payload.put("spokenText", item.spokenText());
            return payload;
        }).toList();
    }

    private List<HistoryItem> readAll() {
        try {
            String raw = Files.readString(historyFile);
            if (raw.isBlank()) {
                return List.of();
            }
            return objectMapper.readValue(raw, new TypeReference<List<HistoryItem>>() {
            });
        } catch (IOException error) {
            throw new IllegalStateException("Failed to read history file: " + historyFile, error);
        }
    }

    private void writeAll(List<HistoryItem> items) {
        try {
            Files.writeString(
                    historyFile,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.CREATE
            );
        } catch (IOException error) {
            throw new IllegalStateException("Failed to write history file: " + historyFile, error);
        }
    }

    private String asString(Object value, String fallback) {
        if (value instanceof String stringValue && !stringValue.isBlank()) {
            return stringValue.trim();
        }
        return fallback;
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
