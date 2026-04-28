package com.bishe10.backend.controller;

import com.bishe10.backend.service.HistoryService;
import com.bishe10.backend.service.LlmService;
import com.bishe10.backend.service.TextToSpeechService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class SystemController {

    @Value("${spring.application.name}")
    private String applicationName;

    private final HistoryService historyService;
    private final LlmService llmService;
    private final TextToSpeechService textToSpeechService;

    public SystemController(
            HistoryService historyService,
            LlmService llmService,
            TextToSpeechService textToSpeechService
    ) {
        this.historyService = historyService;
        this.llmService = llmService;
        this.textToSpeechService = textToSpeechService;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "UP");
        payload.put("application", applicationName);
        payload.put("timestamp", OffsetDateTime.now().toString());
        payload.put("mode", "mvp-live");
        payload.put("historyCount", historyService.count());
        payload.put("llmConfigured", llmService.isConfigured());
        payload.put("ttsConfigured", textToSpeechService.isConfigured());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/api/meta")
    public Map<String, Object> meta() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("project", "面向视障人士的多模态情境感知资讯辅助系统");
        payload.put("stack", List.of("WeChat Mini Program", "uni-app", "Spring Boot", "LLM-compatible API", "Edge TTS"));
        payload.put("deployment", "vultr-live");
        payload.put("capabilities", List.of("news", "vision", "history", "voice", "profile", "compat-mock"));
        payload.put("providers", Map.of(
                "llm", llmService.getProviderLabel(),
                "visionLlm", llmService.getVisionProviderLabel(),
                "tts", textToSpeechService.getProviderLabel()
        ));
        payload.put("models", Map.of(
                "llm", llmService.getModel(),
                "visionLlm", llmService.getVisionModel()
        ));
        payload.put("status", Map.of(
                "llmConfigured", llmService.isConfigured(),
                "visionLlmConfigured", llmService.isVisionConfigured(),
                "ttsConfigured", textToSpeechService.isConfigured(),
                "historyCount", historyService.count()
        ));
        return ApiResponse.ok(payload);
    }
}
