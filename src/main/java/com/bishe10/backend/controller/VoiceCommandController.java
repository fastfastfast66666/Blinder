package com.bishe10.backend.controller;

import com.bishe10.backend.dto.VoiceCommandRequest;
import com.bishe10.backend.service.VoiceCommandService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class VoiceCommandController {

    private final VoiceCommandService voiceCommandService;

    public VoiceCommandController(VoiceCommandService voiceCommandService) {
        this.voiceCommandService = voiceCommandService;
    }

    @PostMapping("/api/voice/command")
    public Map<String, Object> processCommand(@RequestBody(required = false) VoiceCommandRequest request) {
        return ApiResponse.ok(voiceCommandService.understand(request));
    }
}
