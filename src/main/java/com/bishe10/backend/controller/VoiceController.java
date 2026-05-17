package com.bishe10.backend.controller;

import com.bishe10.backend.service.HistoryService;
import com.bishe10.backend.service.TencentAsrService;
import com.bishe10.backend.service.TextToSpeechService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
public class VoiceController {

    private final TextToSpeechService textToSpeechService;
    private final HistoryService historyService;
    private final TencentAsrService tencentAsrService;

    public VoiceController(
            TextToSpeechService textToSpeechService,
            HistoryService historyService,
            TencentAsrService tencentAsrService
    ) {
        this.textToSpeechService = textToSpeechService;
        this.historyService = historyService;
        this.tencentAsrService = tencentAsrService;
    }

    @PostMapping("/api/voice/synthesize")
    public Map<String, Object> synthesize(@RequestBody Map<String, Object> request) {
        String text = request.get("text") instanceof String value ? value.trim() : "";
        String title = request.get("title") instanceof String value ? value.trim() : "语音播报";
        String source = request.get("source") instanceof String value ? value.trim() : "语音服务";

        Map<String, Object> payload = textToSpeechService.synthesize(text);
        if (Boolean.TRUE.equals(request.get("recordHistory")) && Boolean.TRUE.equals(payload.get("available"))) {
            historyService.append("voice", title, text, source, text);
        }
        return ApiResponse.ok(payload);
    }

    @PostMapping(value = "/api/voice/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> transcribe(
            @RequestParam("audio") MultipartFile audio,
            @RequestParam(value = "format", required = false) String format
    ) {
        return ApiResponse.ok(tencentAsrService.transcribe(audio, format));
    }

    @GetMapping("/api/voice/file/{audioId}")
    public ResponseEntity<Resource> file(@PathVariable String audioId) {
        Resource resource = textToSpeechService.resolveAudio(audioId)
                .orElseThrow(() -> new IllegalArgumentException("音频文件不存在"));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000")
                .body(resource);
    }
}
