package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Iterator;

/**
 * Compresses vision images to a smaller JPEG before handing them to the LLM.
 *
 * Strategy:
 * 1. Hash original bytes (SHA-256). Disk cache under runtime-data/vision-cache/<hash>.jpg.
 * 2. On cache hit: return cached bytes directly (~0ms disk read).
 * 3. On cache miss: decode, scale longest edge to MAX_EDGE, JPEG encode at QUALITY,
 *    write to cache, return.
 *
 * If anything fails we fall back to the original bytes so vision still works.
 */
@Service
public class ImageCompressor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageCompressor.class);

    private static final int MAX_EDGE = 1024;            // px, enough for vision LLMs
    private static final float JPEG_QUALITY = 0.78f;
    private static final int SKIP_IF_SMALLER_THAN = 120 * 1024; // bytes: don't bother if already <120KB
    private static final String CACHE_DIR_NAME = "vision-cache";

    private final Path cacheDir;

    public ImageCompressor(Bishe10Properties properties) {
        this.cacheDir = Path.of(properties.getStorage().getRootDir(), CACHE_DIR_NAME);
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(cacheDir);
    }

    public static class Result {
        public final byte[] bytes;
        public final String mimeType;
        public final boolean cacheHit;
        public final boolean compressed;
        public final int originalBytes;
        public final long elapsedMs;
        public final String hash;

        Result(byte[] bytes, String mimeType, boolean cacheHit, boolean compressed, int originalBytes, long elapsedMs, String hash) {
            this.bytes = bytes;
            this.mimeType = mimeType;
            this.cacheHit = cacheHit;
            this.compressed = compressed;
            this.originalBytes = originalBytes;
            this.elapsedMs = elapsedMs;
            this.hash = hash;
        }
    }

    /**
     * Returns compressed bytes ready for the LLM. Never returns null: on any error
     * we return the original bytes.
     */
    public Result compressForLlm(byte[] original, String originalMimeType) {
        long start = System.nanoTime();

        if (original == null || original.length == 0) {
            return new Result(original, originalMimeType, false, false, 0, 0, "");
        }

        int originalSize = original.length;

        // Skip small images — compression overhead not worth it.
        if (originalSize <= SKIP_IF_SMALLER_THAN) {
            String quickHash = sha256(original);
            return new Result(original, originalMimeType, false, false, originalSize, elapsedMs(start), quickHash);
        }

        String hash = sha256(original);
        Path cachedPath = cacheDir.resolve(hash + ".jpg");

        if (Files.exists(cachedPath)) {
            try {
                byte[] cached = Files.readAllBytes(cachedPath);
                long elapsed = elapsedMs(start);
                LOGGER.info("vision-cache hit hash={} original={}KB cached={}KB elapsed={}ms",
                        hash.substring(0, 10), originalSize / 1024, cached.length / 1024, elapsed);
                return new Result(cached, "image/jpeg", true, true, originalSize, elapsed, hash);
            } catch (IOException error) {
                LOGGER.warn("vision-cache read failed hash={}", hash.substring(0, 10), error);
                // fall through to recompress
            }
        }

        try {
            byte[] compressed = compressJpeg(original);
            if (compressed == null) {
                return new Result(original, originalMimeType, false, false, originalSize, elapsedMs(start), hash);
            }

            // If compression made it bigger (rare but possible), keep original.
            if (compressed.length >= originalSize) {
                LOGGER.info("vision-compress skipped (no shrink) hash={} original={}KB",
                        hash.substring(0, 10), originalSize / 1024);
                return new Result(original, originalMimeType, false, false, originalSize, elapsedMs(start), hash);
            }

            try {
                Path tmp = Files.createTempFile(cacheDir, hash + ".", ".jpg.tmp");
                Files.write(tmp, compressed);
                Files.move(tmp, cachedPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException error) {
                LOGGER.warn("vision-cache write failed hash={}", hash.substring(0, 10), error);
                // still return compressed bytes even if cache persist failed
            }

            long elapsed = elapsedMs(start);
            int savedPct = (int) Math.round(100.0 * (originalSize - compressed.length) / originalSize);
            LOGGER.info("vision-compress hash={} original={}KB compressed={}KB saved={}% elapsed={}ms",
                    hash.substring(0, 10), originalSize / 1024, compressed.length / 1024, savedPct, elapsed);
            return new Result(compressed, "image/jpeg", false, true, originalSize, elapsed, hash);

        } catch (Exception error) {
            LOGGER.warn("vision-compress failed, using original bytes", error);
            return new Result(original, originalMimeType, false, false, originalSize, elapsedMs(start), hash);
        }
    }

    private byte[] compressJpeg(byte[] original) throws IOException {
        BufferedImage source;
        try (ByteArrayInputStream in = new ByteArrayInputStream(original)) {
            source = ImageIO.read(in);
        }
        if (source == null) {
            return null;
        }

        int srcW = source.getWidth();
        int srcH = source.getHeight();
        int longest = Math.max(srcW, srcH);
        double scale = longest > MAX_EDGE ? (double) MAX_EDGE / longest : 1.0;

        BufferedImage resized;
        if (scale < 1.0) {
            int targetW = (int) Math.round(srcW * scale);
            int targetH = (int) Math.round(srcH * scale);
            resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.drawImage(source, 0, 0, targetW, targetH, null);
            } finally {
                g.dispose();
            }
        } else if (source.getType() == BufferedImage.TYPE_INT_RGB) {
            resized = source;
        } else {
            // JPEG writer can't handle alpha — flatten to RGB on white background.
            resized = new BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            try {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, srcW, srcH);
                g.drawImage(source, 0, 0, null);
            } finally {
                g.dispose();
            }
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);

            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(16 * 1024, original.length / 4));
            try (MemoryCacheImageOutputStream imageOut = new MemoryCacheImageOutputStream(out)) {
                writer.setOutput(imageOut);
                writer.write(null, new IIOImage(resized, null, null), param);
            }
            return out.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception error) {
            // SHA-256 is guaranteed; fall back to length-based key if somehow missing.
            return Integer.toHexString(bytes.length) + "-" + bytes.length;
        }
    }

    @SuppressWarnings("unused")
    private String toUtf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
