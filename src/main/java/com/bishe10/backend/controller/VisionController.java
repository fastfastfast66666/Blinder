package com.bishe10.backend.controller;

import com.bishe10.backend.service.LlmService;
import com.bishe10.backend.service.VisionSampleService;
import com.bishe10.backend.service.VisionService;
import com.bishe10.backend.support.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class VisionController {

    private final VisionService visionService;
    private final VisionSampleService visionSampleService;
    private final LlmService llmService;
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public VisionController(VisionService visionService, VisionSampleService visionSampleService, LlmService llmService) {
        this.visionService = visionService;
        this.visionSampleService = visionSampleService;
        this.llmService = llmService;
    }

    @GetMapping("/api/vision/samples")
    public Map<String, Object> samples() {
        return ApiResponse.ok(Map.of("items", visionSampleService.listSamples()));
    }

    @GetMapping("/api/vision/samples/{sampleKey}/image")
    public ResponseEntity<Resource> sampleImage(@PathVariable String sampleKey) {
        Resource resource = visionSampleService.loadImage(sampleKey).orElse(null);
        if (resource == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000")
                .body(resource);
    }

    @PostMapping(value = "/api/vision/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> analyze(@RequestBody(required = false) Map<String, Object> request) {
        String scene = extractScene(request);
        String sampleKey = extractSampleKey(request);

        if (!sampleKey.isBlank()) {
            VisionSampleService.SamplePayload sample = visionSampleService.loadForAnalysis(sampleKey).orElse(null);
            if (sample == null) {
                return ApiResponse.fail(400, "参考图片不存在");
            }
            Map<String, Object> payload = new LinkedHashMap<>(visionService.analyze(
                    scene.isBlank() ? sample.sample().scene() : scene,
                    sample.bytes(),
                    sample.mimeType()
            ));
            payload.put("sampleKey", sample.sample().key());
            payload.put("sampleTitle", sample.sample().title());
            payload.put("sampleImageUrl", "/api/vision/samples/" + sample.sample().key() + "/image");
            return ApiResponse.ok(payload);
        }

        return ApiResponse.ok(visionService.analyze(scene, null, null));
    }

    @PostMapping(value = "/api/vision/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> analyzeUpload(
            @RequestPart(value = "scene", required = false) String scene,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) throws Exception {
        byte[] bytes = image != null && !image.isEmpty() ? image.getBytes() : null;
        String mimeType = image != null ? image.getContentType() : null;
        return ApiResponse.ok(visionService.analyze(scene, bytes, mimeType));
    }

    @PostMapping(value = "/api/vision/analyze/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestBody(required = false) Map<String, Object> request) {
        String scene = extractScene(request);
        String sampleKey = extractSampleKey(request);

        byte[] imageBytes = null;
        String mimeType = null;
        Map<String, Object> meta = new LinkedHashMap<>();
        if (!sampleKey.isBlank()) {
            VisionSampleService.SamplePayload sample = visionSampleService.loadForAnalysis(sampleKey).orElse(null);
            if (sample == null) {
                return errorEmitter("参考图片不存在");
            }
            scene = scene.isBlank() ? sample.sample().scene() : scene;
            imageBytes = sample.bytes();
            mimeType = sample.mimeType();
            meta.put("sampleKey", sample.sample().key());
            meta.put("sampleTitle", sample.sample().title());
            meta.put("sampleImageUrl", "/api/vision/samples/" + sample.sample().key() + "/image");
        }

        return streamAnalyze(scene, imageBytes, mimeType, meta);
    }

    @PostMapping(value = "/api/vision/analyze/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeUploadStream(
            @RequestPart(value = "scene", required = false) String scene,
            @RequestPart(value = "image", required = false) MultipartFile image
    ) throws Exception {
        byte[] bytes = image != null && !image.isEmpty() ? image.getBytes() : null;
        String mimeType = image != null ? image.getContentType() : null;
        return streamAnalyze(scene, bytes, mimeType, Map.of());
    }

    private String extractScene(Map<String, Object> request) {
        if (request != null && request.get("scene") instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return "";
    }

    private String extractSampleKey(Map<String, Object> request) {
        if (request != null && request.get("sampleKey") instanceof String value && !value.isBlank()) {
            return value.trim();
        }
        return "";
    }

    private SseEmitter streamAnalyze(String scene, byte[] imageBytes, String mimeType, Map<String, Object> extraMeta) {
        // Generous SSE timeout so slow vision LLMs (e.g. GLM-4.6v with thinking) can finish.
        SseEmitter emitter = new SseEmitter(150_000L);
        streamExecutor.execute(() -> {
            try {
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("provider", llmService.getVisionProviderLabel());
                meta.put("model", llmService.getVisionModel());
                meta.put("scene", scene == null || scene.isBlank() ? "general" : scene);
                meta.putAll(extraMeta);
                sendEvent(emitter, "meta", meta);
                sendEvent(emitter, "status", Map.of(
                        "phase", "queued",
                        "message", "已接收识图请求，正在准备分析。"
                ));

                Map<String, Object> payload = visionService.analyzeStreaming(
                        scene,
                        imageBytes,
                        mimeType,
                        preview -> sendEvent(emitter, "preview", Map.of("text", preview)),
                        status -> sendEvent(emitter, "status", status)
                );

                if (!extraMeta.isEmpty()) {
                    payload = new LinkedHashMap<>(payload);
                    payload.putAll(extraMeta);
                }
                sendEvent(emitter, "result", payload);
                sendEvent(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (Exception error) {
                try {
                    sendEvent(emitter, "error", Map.of("message", error.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    private SseEmitter errorEmitter(String message) {
        SseEmitter emitter = new SseEmitter(1_000L);
        try {
            sendEvent(emitter, "error", Map.of("message", message));
            sendEvent(emitter, "done", Map.of("ok", false));
            emitter.complete();
        } catch (Exception error) {
            emitter.completeWithError(error);
        }
        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (Exception error) {
            throw new IllegalStateException("Failed to send SSE event: " + event, error);
        }
    }
}
