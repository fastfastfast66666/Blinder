package com.bishe10.backend.controller;

import com.bishe10.backend.model.HistoryItem;
import com.bishe10.backend.service.HistoryService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HistoryController {

    private final HistoryService historyService;

    public HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/api/history")
    public Map<String, Object> history(@RequestParam(defaultValue = "12") int limit) {
        return ApiResponse.ok(Map.of(
                "items", historyService.toPayload(historyService.latest(limit))
        ));
    }

    @PostMapping("/api/history")
    public Map<String, Object> append(@RequestBody Map<String, Object> request) {
        HistoryItem item = historyService.append(request);
        return ApiResponse.ok(Map.of("item", item));
    }
}
