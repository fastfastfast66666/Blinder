package com.bishe10.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class VisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VisionService.class);

    private final LlmService llmService;
    private final HistoryService historyService;
    private final ImageCompressor imageCompressor;

    // Cache successful LLM results keyed on (image hash + scene).
    private final Map<String, Map<String, Object>> resultCache = new ConcurrentHashMap<>();

    public VisionService(LlmService llmService, HistoryService historyService, ImageCompressor imageCompressor) {
        this.llmService = llmService;
        this.historyService = historyService;
        this.imageCompressor = imageCompressor;
    }

    public Map<String, Object> analyze(String scene, byte[] imageBytes, String mimeType) {
        String normalizedScene = normalizeScene(scene);
        ImageCompressor.Result compressed = imageCompressor.compressForLlm(imageBytes, mimeType);

        String resultKey = buildResultKey(compressed.hash, normalizedScene);
        Map<String, Object> cached = resultKey == null ? null : resultCache.get(resultKey);
        if (cached != null) {
            LOGGER.info("vision-result cache hit key={}", resultKey);
            Map<String, Object> copy = new LinkedHashMap<>(cached);
            copy.put("cached", true);
            appendHistory(normalizedScene, copy);
            return copy;
        }

        Map<String, Object> payload = analyzeOrFallback(normalizedScene, compressed, null, null);
        if (resultKey != null && isLlmPayload(payload)) {
            resultCache.put(resultKey, new LinkedHashMap<>(payload));
        }

        appendHistory(normalizedScene, payload);
        return payload;
    }

    public Map<String, Object> analyzeStreaming(
            String scene,
            byte[] imageBytes,
            String mimeType,
            Consumer<String> onPreview,
            Consumer<Map<String, Object>> onStatus
    ) {
        String normalizedScene = normalizeScene(scene);

        if (imageBytes == null || imageBytes.length == 0) {
            Map<String, Object> payload = normalizePayload(fallbackPayload(normalizedScene), normalizedScene, false, false);
            emitStatus(onStatus, "fallback", "未检测到图片，已切换为参考播报。");
            emitSyntheticPreview(asString(payload.get("recognizedText")), onPreview);
            appendHistory(normalizedScene, payload);
            return payload;
        }

        ImageCompressor.Result compressed = imageCompressor.compressForLlm(imageBytes, mimeType);
        if (compressed.compressed) {
            emitStatus(
                    onStatus,
                    "compress",
                    String.format("图片已%s为 %dKB，准备发送到识图模型。", compressed.cacheHit ? "命中缓存并压缩" : "压缩", compressed.bytes.length / 1024)
            );
        }

        String resultKey = buildResultKey(compressed.hash, normalizedScene);
        Map<String, Object> cached = resultKey == null ? null : resultCache.get(resultKey);
        if (cached != null) {
            LOGGER.info("vision-result cache hit key={}", resultKey);
            emitStatus(onStatus, "cached", "命中相同图片的识图缓存，直接返回上次结果。");
            Map<String, Object> copy = new LinkedHashMap<>(cached);
            copy.put("cached", true);
            emitSyntheticPreview(asString(copy.get("recognizedText")), onPreview);
            appendHistory(normalizedScene, copy);
            return copy;
        }

        emitStatus(onStatus, "model", "已连接识图模型，正在生成分析结果。");
        StringBuilder rawStream = new StringBuilder();
        AtomicReference<String> lastPreview = new AtomicReference<>("");

        Map<String, Object> payload = analyzeOrFallback(
                normalizedScene,
                compressed,
                chunk -> {
                    rawStream.append(chunk);
                    String preview = extractPreviewText(rawStream.toString());
                    if (!preview.isBlank() && !preview.equals(lastPreview.get())) {
                        lastPreview.set(preview);
                        if (onPreview != null) {
                            onPreview.accept(preview);
                        }
                    }
                },
                onStatus
        );

        if (!isLlmPayload(payload)) {
            emitSyntheticPreview(asString(payload.get("recognizedText")), onPreview);
        }

        String finalPreview = firstNonBlank(
                asString(payload.get("recognizedText")),
                asString(payload.get("readingText")),
                asString(payload.get("voiceBroadcast"))
        );
        if (!finalPreview.isBlank() && !finalPreview.equals(lastPreview.get()) && onPreview != null) {
            onPreview.accept(finalPreview);
        }

        if (resultKey != null && isLlmPayload(payload)) {
            resultCache.put(resultKey, new LinkedHashMap<>(payload));
        }

        appendHistory(normalizedScene, payload);
        return payload;
    }

    private Map<String, Object> analyzeOrFallback(
            String scene,
            ImageCompressor.Result compressed,
            Consumer<String> onChunk,
            Consumer<Map<String, Object>> onStatus
    ) {
        boolean hasImage = compressed.bytes != null && compressed.bytes.length > 0;
        if (!hasImage) {
            LOGGER.warn("vision analyze fallback: no image bytes scene={}", scene);
            return normalizePayload(fallbackPayload(scene), scene, false, false);
        }

        if (!llmService.isVisionConfigured()) {
            LOGGER.warn("vision analyze fallback: vision LLM is not configured scene={} hash={}", scene, safeHash(compressed.hash));
            if (onStatus != null) {
                emitStatus(onStatus, "fallback", "识图模型当前未启用，已切换为参考播报。");
            }
            return normalizePayload(fallbackPayload(scene), scene, false, true);
        }

        Optional<Map<String, Object>> llmResult = onChunk == null
                ? llmService.analyzeVision(scene, compressed.bytes, compressed.mimeType)
                : llmService.analyzeVisionStreaming(scene, compressed.bytes, compressed.mimeType, onChunk);

        if (llmResult.isPresent()) {
            return normalizePayload(llmResult.get(), scene, true, true);
        }

        LOGGER.warn(
                "vision analyze fallback: vision LLM returned empty result scene={} hash={} bytes={} mimeType={}",
                scene,
                safeHash(compressed.hash),
                compressed.bytes.length,
                compressed.mimeType
        );
        if (onStatus != null) {
            emitStatus(onStatus, "fallback", "识图模型没有返回有效结果，已切换为参考播报。");
        }
        return normalizePayload(fallbackPayload(scene), scene, false, true);
    }

    private String buildResultKey(String imageHash, String scene) {
        if (imageHash == null || imageHash.isBlank()) {
            return null;
        }
        return imageHash + ":" + (scene == null ? "" : scene);
    }

    private boolean isLlmPayload(Map<String, Object> payload) {
        Object mode = payload == null ? null : payload.get("analysisMode");
        return "llm-vision".equals(mode);
    }

    private Map<String, Object> normalizePayload(Map<String, Object> raw, String scene, boolean fromLlm, boolean hasImage) {
        Map<String, Object> fallback = fallbackPayload(scene);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scene", scene);
        payload.put("sceneTitle", sceneTitle(scene));

        String recognizedText;
        String readingText;
        String voiceBroadcast;
        if (isTextReadingScene(scene)) {
            recognizedText = firstNonBlank(
                    asText(raw.get("recognizedText")),
                    asText(raw.get("readingText")),
                    asText(raw.get("voiceBroadcast")),
                    asText(fallback.get("recognizedText"))
            );
            readingText = firstNonBlank(
                    asText(raw.get("readingText")),
                    normalizeReadingText(recognizedText),
                    asText(raw.get("voiceBroadcast")),
                    asText(fallback.get("readingText")),
                    asText(fallback.get("voiceBroadcast"))
            );
            voiceBroadcast = firstNonBlank(
                    asText(raw.get("voiceBroadcast")),
                    readingText,
                    recognizedText,
                    asText(fallback.get("voiceBroadcast"))
            );
        } else {
            recognizedText = firstNonBlank(
                    asText(raw.get("recognizedText")),
                    asText(raw.get("voiceBroadcast")),
                    asText(fallback.get("recognizedText"))
            );
            readingText = firstNonBlank(
                    asText(raw.get("readingText")),
                    asText(raw.get("voiceBroadcast")),
                    recognizedText
            );
            voiceBroadcast = firstNonBlank(
                    asText(raw.get("voiceBroadcast")),
                    readingText,
                    recognizedText,
                    asText(fallback.get("voiceBroadcast"))
            );
        }

        payload.put("recognizedText", recognizedText);
        payload.put("readingText", readingText);
        payload.put("voiceBroadcast", voiceBroadcast);
        payload.put("textLength", countVisibleCharacters(recognizedText));
        payload.put("sceneTips", toStringList(raw.get("sceneTips"), toStringList(fallback.get("sceneTips"), List.of())));
        payload.put("safetyLevel", normalizeSafetyLevel(firstNonBlank(
                asString(raw.get("safetyLevel")),
                asString(fallback.get("safetyLevel"))
        )));
        payload.put("analysisMode", fromLlm ? "llm-vision" : "template-fallback");
        payload.put("provider", fromLlm ? llmService.getVisionProviderLabel() : "built-in");
        payload.put("hasImage", hasImage);
        payload.put("model", fromLlm ? llmService.getVisionModel() : "template");
        return payload;
    }

    private Map<String, Object> fallbackPayload(String scene) {
        return switch (scene) {
            case "crossroad" -> Map.of(
                    "recognizedText", "识别结果：前方为路口与斑马线区域，请先确认红绿灯和车流方向，再判断是否直行通过。",
                    "voiceBroadcast", "前方是路口和斑马线，请先确认红绿灯和车流，再安全通过。",
                    "sceneTips", List.of(
                            "优先播报红绿灯状态和车辆方向",
                            "强调直道中断、围挡和转弯风险",
                            "建议句式尽量简短直接"
                    ),
                    "safetyLevel", "high"
            );
            case "supermarket" -> Map.of(
                    "recognizedText", "识别结果：当前位置位于货架通道，左侧为饮料货架，右侧保留一人宽通道，可缓慢继续前进。",
                    "voiceBroadcast", "前方是货架区域，左侧为饮料货架，右侧通道可继续前进。",
                    "sceneTips", List.of(
                            "先说明货架类别，再说明可通行方向",
                            "优先播报购物篮、促销台和人员密集点",
                            "尽量避免描述无关装饰信息"
                    ),
                    "safetyLevel", "medium"
            );
            case "text-reading" -> Map.of(
                    "recognizedText", "当前是文本阅读模式。请拍摄书页、小说、说明书或告示牌，并尽量让文字完整、端正、清晰地进入画面。",
                    "readingText", "请上传需要朗读的文字图片，例如书页、菜单、说明书或小说页面。我会先提取全文，再整理断句后进行语音朗读。",
                    "voiceBroadcast", "请上传需要朗读的文字图片。我会先提取全文，再整理断句后进行语音朗读。",
                    "sceneTips", List.of(
                            "尽量单页拍摄减少遮挡",
                            "保持画面端正并避免反光",
                            "识别后可直接语音朗读"
                    ),
                    "safetyLevel", "low"
            );
            default -> Map.of(
                    "recognizedText", "识别结果：前方通道整体可通行，右前方可能存在台阶或门槛，建议减速并留意脚下。",
                    "voiceBroadcast", "前方通道基本可通行，右前方可能有台阶，请减速留意脚下。",
                    "sceneTips", List.of(
                            "优先提示障碍物和可通行方向",
                            "给出相对方位，不要只说物体名称",
                            "播报内容应先安全，再补环境描述"
                    ),
                    "safetyLevel", "medium"
            );
        };
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return "general";
        }
        return switch (scene.trim()) {
            case "crossroad", "supermarket", "text-reading" -> scene.trim();
            case "text_reading", "textReading", "reading", "ocr" -> "text-reading";
            default -> "general";
        };
    }

    private String sceneTitle(String scene) {
        return switch (scene) {
            case "crossroad" -> "十字路口识别";
            case "supermarket" -> "超市货架识别";
            case "text-reading" -> "文本阅读";
            default -> "通用环境识别";
        };
    }

    private String normalizeSafetyLevel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "low", "safe", "minor" -> "low";
            case "medium", "moderate", "caution", "warning" -> "medium";
            case "high", "danger", "critical" -> "high";
            default -> "medium";
        };
    }

    private String asString(Object value) {
        return value instanceof String stringValue ? stringValue.trim() : "";
    }

    private String asText(Object value) {
        if (value instanceof String stringValue) {
            return stringValue.trim();
        }
        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(item -> item == null ? "" : item.toString().trim())
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.joining("；"));
        }
        return value == null ? "" : value.toString().trim();
    }

    private List<String> toStringList(Object value, List<String> fallback) {
        if (value instanceof List<?> listValue) {
            List<String> result = listValue.stream()
                    .map(item -> item == null ? "" : item.toString().trim())
                    .filter(item -> !item.isBlank())
                    .limit(3)
                    .toList();
            if (!result.isEmpty()) {
                return result;
            }
        }
        if (value instanceof String textValue && !textValue.isBlank()) {
            List<String> result = Arrays.stream(textValue.split("[；。,\n]"))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .limit(3)
                    .toList();
            if (!result.isEmpty()) {
                return result;
            }
        }
        return fallback;
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String extractPreviewText(String raw) {
        String normalized = raw == null ? "" : raw
                .replace("```json", "")
                .replace("```", "")
                .replace("\r", "");
        String recognized = extractPartialJsonString(normalized, "recognizedText");
        if (!recognized.isBlank()) {
            return recognized;
        }
        String readingText = extractPartialJsonString(normalized, "readingText");
        if (!readingText.isBlank()) {
            return readingText;
        }
        return extractPartialJsonString(normalized, "voiceBroadcast");
    }

    private String extractPartialJsonString(String raw, String field) {
        String key = "\"" + field + "\"";
        int keyIndex = raw.indexOf(key);
        if (keyIndex < 0) {
            return "";
        }

        int colonIndex = raw.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return "";
        }

        int firstQuote = raw.indexOf('"', colonIndex + 1);
        if (firstQuote < 0) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        boolean escaping = false;
        for (int index = firstQuote + 1; index < raw.length(); index++) {
            char current = raw.charAt(index);
            if (escaping) {
                switch (current) {
                    case 'n' -> builder.append('\n');
                    case 't' -> builder.append('\t');
                    case 'r' -> builder.append('\r');
                    default -> builder.append(current);
                }
                escaping = false;
                continue;
            }
            if (current == '\\') {
                escaping = true;
                continue;
            }
            if (current == '"') {
                break;
            }
            builder.append(current);
        }
        return builder.toString().trim();
    }

    private void emitSyntheticPreview(String text, Consumer<String> onPreview) {
        if (onPreview == null || text == null || text.isBlank()) {
            return;
        }
        for (int index = 0; index < text.length(); index += 8) {
            onPreview.accept(text.substring(0, Math.min(text.length(), index + 8)));
        }
    }

    private void emitStatus(Consumer<Map<String, Object>> onStatus, String phase, String message) {
        if (onStatus == null) {
            return;
        }
        onStatus.accept(Map.of(
                "phase", phase,
                "message", message
        ));
    }

    private void appendHistory(String scene, Map<String, Object> payload) {
        historyService.append(
                "vision",
                sceneTitle(scene),
                abbreviateForHistory(firstNonBlank(
                        asString(payload.get("recognizedText")),
                        asString(payload.get("readingText")),
                        asString(payload.get("voiceBroadcast"))
                )),
                "情境识别",
                firstNonBlank(
                        asString(payload.get("readingText")),
                        asString(payload.get("voiceBroadcast")),
                        asString(payload.get("recognizedText"))
                )
        );
    }

    private boolean isTextReadingScene(String scene) {
        return "text-reading".equals(scene);
    }

    private int countVisibleCharacters(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) text.chars()
                .filter(ch -> !Character.isWhitespace(ch))
                .count();
    }

    private String normalizeReadingText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String normalized = text
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')
                .trim();
        if (normalized.isBlank()) {
            return "";
        }

        String[] lines = normalized.split("\n");
        List<String> paragraphs = new ArrayList<>();
        StringBuilder currentParagraph = new StringBuilder();

        for (String line : lines) {
            String cleanedLine = line == null ? "" : line.trim().replaceAll("[\\t ]+", " ");
            if (cleanedLine.isBlank()) {
                flushParagraph(paragraphs, currentParagraph);
                continue;
            }

            if (currentParagraph.length() == 0) {
                currentParagraph.append(cleanedLine);
                continue;
            }

            if (looksLikeHeading(cleanedLine)) {
                flushParagraph(paragraphs, currentParagraph);
                currentParagraph.append(cleanedLine);
                continue;
            }

            if (needsSpacer(currentParagraph.charAt(currentParagraph.length() - 1), cleanedLine.charAt(0))) {
                currentParagraph.append(' ');
            }
            currentParagraph.append(cleanedLine);
        }

        flushParagraph(paragraphs, currentParagraph);
        if (paragraphs.isEmpty()) {
            return normalized;
        }
        return String.join("\n\n", paragraphs);
    }

    private void flushParagraph(List<String> paragraphs, StringBuilder currentParagraph) {
        if (currentParagraph.length() == 0) {
            return;
        }
        paragraphs.add(currentParagraph.toString().trim());
        currentParagraph.setLength(0);
    }

    private boolean looksLikeHeading(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.matches("^(第[零一二三四五六七八九十百千万两0-9]+[章节回部卷篇节].*|Chapter\\s+\\d+.*|CHAPTER\\s+\\d+.*)$");
    }

    private boolean needsSpacer(char previous, char next) {
        return isAsciiWord(previous) && isAsciiWord(next);
    }

    private boolean isAsciiWord(char value) {
        return (value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9');
    }

    private String abbreviateForHistory(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 120) + "…";
    }

    private String safeHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "none";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }
}
