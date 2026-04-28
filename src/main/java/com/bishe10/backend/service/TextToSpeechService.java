package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class TextToSpeechService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextToSpeechService.class);

    private final Bishe10Properties.Tts properties;
    private final Path audioDir;

    public TextToSpeechService(Bishe10Properties bishe10Properties) {
        this.properties = bishe10Properties.getTts();
        this.audioDir = Path.of(bishe10Properties.getStorage().getRootDir(), "audio");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(audioDir);
    }

    public Map<String, Object> synthesize(String text) {
        String normalizedText = text == null ? "" : text.trim();
        if (normalizedText.isBlank()) {
            throw new IllegalArgumentException("播报文本不能为空");
        }

        String audioId = digest(normalizedText);
        Path output = audioDir.resolve(audioId + ".mp3");
        boolean cached = Files.exists(output);
        boolean available = cached;

        if (!cached && properties.isEnabled()) {
            available = generateAudio(normalizedText, output);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("audioId", audioId);
        payload.put("audioUrl", available ? "/api/voice/file/" + audioId : "");
        payload.put("available", available);
        payload.put("cached", cached && available);
        payload.put("provider", properties.getProviderLabel());
        payload.put("text", normalizedText);
        return payload;
    }

    public Optional<Resource> resolveAudio(String audioId) {
        if (audioId == null || !audioId.matches("[a-f0-9]{16}")) {
            return Optional.empty();
        }
        Path file = audioDir.resolve(audioId + ".mp3");
        if (Files.notExists(file)) {
            return Optional.empty();
        }
        return Optional.of(new PathResource(file));
    }

    public String getProviderLabel() {
        return properties.getProviderLabel();
    }

    public boolean isConfigured() {
        return properties.isEnabled() && Files.exists(Path.of(properties.getScriptPath()));
    }

    private boolean generateAudio(String text, Path output) {
        Path scriptPath = Path.of(properties.getScriptPath());
        if (Files.notExists(scriptPath)) {
            LOGGER.warn("TTS script not found: {}", scriptPath);
            return false;
        }

        ProcessBuilder builder = new ProcessBuilder(
                properties.getPythonBin(),
                scriptPath.toString(),
                "--text",
                text,
                "--output",
                output.toString(),
                "--voice",
                properties.getVoice()
        );
        builder.redirectErrorStream(true);

        try {
            Process process = builder.start();
            String logs;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                logs = reader.lines().reduce("", (left, right) -> left + right + System.lineSeparator());
            }
            boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("TTS process timed out after {}", Duration.ofSeconds(properties.getTimeoutSeconds()));
                return false;
            }
            if (process.exitValue() != 0 || Files.notExists(output)) {
                LOGGER.warn("TTS generation failed. exit={}, logs={}", process.exitValue(), logs);
                return false;
            }
            return true;
        } catch (InterruptedException error) {
            LOGGER.warn("TTS generation failed", error);
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException error) {
            LOGGER.warn("TTS generation failed", error);
            return false;
        }
    }

    private String digest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                builder.append(String.format("%02x", bytes[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }
}
