package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TencentAsrService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TencentAsrService.class);
    private static final String SERVICE = "asr";
    private static final String ACTION = "SentenceRecognition";
    private static final String VERSION = "2019-06-14";
    private static final int MAX_INLINE_AUDIO_BYTES = 3 * 1024 * 1024;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    private final Bishe10Properties.Asr properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public TencentAsrService(Bishe10Properties bishe10Properties, ObjectMapper objectMapper) {
        this.properties = bishe10Properties.getAsr();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Map<String, Object> transcribe(MultipartFile audio, String requestedFormat) {
        Map<String, Object> unavailable = unavailableReason(audio);
        if (unavailable != null) {
            return unavailable;
        }

        try {
            byte[] audioBytes = audio.getBytes();
            if (audioBytes.length > MAX_INLINE_AUDIO_BYTES) {
                return unavailable("录音文件超过腾讯云一句话识别 3MB 限制，请把指令说短一点。");
            }

            String voiceFormat = normalizeFormat(requestedFormat);
            LOGGER.info("Tencent ASR upload filename={} size={} format={}",
                    audio.getOriginalFilename(), audioBytes.length, voiceFormat);
            String payload = objectMapper.writeValueAsString(buildRequestBody(audioBytes, voiceFormat));
            HttpRequest request = buildTencentRequest(payload);
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("Tencent ASR request failed with status {}: {}", response.statusCode(), response.body());
                return unavailable("腾讯云语音识别请求失败：" + response.statusCode());
            }
            return parseTencentResponse(response.body());
        } catch (Exception error) {
            LOGGER.warn("Tencent ASR failed", error);
            return unavailable("腾讯云语音识别暂不可用：" + error.getMessage());
        }
    }

    private Map<String, Object> unavailableReason(MultipartFile audio) {
        if (!isConfigured()) {
            return unavailable("后端还没有配置腾讯云 ASR 密钥。");
        }
        if (audio == null || audio.isEmpty()) {
            return unavailable("没有收到录音文件。");
        }
        return null;
    }

    private boolean isConfigured() {
        return properties.isEnabled()
                && hasText(properties.getBaseUrl())
                && hasText(properties.getSecretId())
                && hasText(properties.getSecretKey())
                && hasText(properties.getRegion());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeFormat(String requestedFormat) {
        String value = hasText(requestedFormat) ? requestedFormat.trim().toLowerCase() : properties.getVoiceFormat();
        if (!hasText(value)) {
            return "mp3";
        }
        if (value.contains("/")) {
            value = value.substring(value.lastIndexOf('/') + 1);
        }
        if ("mpeg".equals(value)) {
            return "mp3";
        }
        return value.replace(".", "");
    }

    private Map<String, Object> buildRequestBody(byte[] audioBytes, String voiceFormat) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ProjectId", 0);
        body.put("SubServiceType", 2);
        body.put("EngSerViceType", hasText(properties.getEngineModelType()) ? properties.getEngineModelType() : "16k_zh");
        body.put("SourceType", 1);
        body.put("VoiceFormat", voiceFormat);
        body.put("Data", Base64.getEncoder().encodeToString(audioBytes));
        body.put("DataLen", audioBytes.length);
        body.put("FilterPunc", 0);
        body.put("ConvertNumMode", 1);
        return body;
    }

    private HttpRequest buildTencentRequest(String payload) throws Exception {
        URI uri = URI.create(normalizeBaseUrl(properties.getBaseUrl()));
        String host = uri.getHost();
        long timestamp = Instant.now().getEpochSecond();
        String date = DATE_FORMATTER.format(Instant.ofEpochSecond(timestamp));
        String contentType = "application/json; charset=utf-8";

        String authorization = buildAuthorization(payload, host, contentType, timestamp, date);

        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(Math.max(5, properties.getTimeoutSeconds())))
                .header("Content-Type", contentType)
                .header("X-TC-Action", ACTION)
                .header("X-TC-Version", VERSION)
                .header("X-TC-Region", properties.getRegion())
                .header("X-TC-Timestamp", String.valueOf(timestamp))
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private String normalizeBaseUrl(String raw) {
        String value = raw == null || raw.isBlank() ? "https://asr.tencentcloudapi.com" : raw.trim();
        return value.replaceAll("/+$", "");
    }

    private String buildAuthorization(
            String payload,
            String host,
            String contentType,
            long timestamp,
            String date
    ) throws Exception {
        String canonicalHeaders = "content-type:" + contentType + "\n" + "host:" + host + "\n";
        String signedHeaders = "content-type;host";
        String canonicalRequest = "POST\n"
                + "/\n"
                + "\n"
                + canonicalHeaders
                + "\n"
                + signedHeaders
                + "\n"
                + sha256Hex(payload);

        String credentialScope = date + "/" + SERVICE + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n"
                + timestamp + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac256(("TC3" + properties.getSecretKey()).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac256(secretDate, SERVICE);
        byte[] secretSigning = hmac256(secretService, "tc3_request");
        String signature = toHex(hmac256(secretSigning, stringToSign));

        return "TC3-HMAC-SHA256 "
                + "Credential=" + properties.getSecretId() + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private Map<String, Object> parseTencentResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode errorNode = root.at("/Response/Error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String code = root.at("/Response/Error/Code").asText("");
            String message = root.at("/Response/Error/Message").asText("腾讯云语音识别失败");
            return unavailable(code.isBlank() ? message : code + "：" + message);
        }

        String text = root.at("/Response/Result").asText("");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", !text.isBlank());
        payload.put("text", text);
        payload.put("provider", properties.getProviderLabel());
        payload.put("engineModelType", properties.getEngineModelType());
        payload.put("audioDuration", root.at("/Response/AudioDuration").asInt(0));
        payload.put("requestId", root.at("/Response/RequestId").asText(""));
        payload.put("message", text.isBlank() ? "腾讯云没有识别到有效文字。" : "success");
        return payload;
    }

    private Map<String, Object> unavailable(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", false);
        payload.put("text", "");
        payload.put("provider", properties.getProviderLabel());
        payload.put("message", message);
        return payload;
    }

    private String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return toHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] hmac256(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    private String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            out.append(String.format("%02x", item & 0xff));
        }
        return out.toString();
    }
}
