package com.bishe10.backend.service;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ArticleIdGenerator {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
            "spm", "from", "source", "seid", "share_token"
    );

    public String articleId(String source, String url, String title, OffsetDateTime publishTime) {
        String normalizedSource = safe(source);
        String canonicalUrl = canonicalUrl(url);
        if (!canonicalUrl.isBlank()) {
            return sha256Hex(normalizedSource + "|" + canonicalUrl);
        }
        String publishDate = publishTime == null ? LocalDate.now().toString() : publishTime.toLocalDate().toString();
        return sha256Hex(normalizedSource + "|" + normalizedTitle(title) + "|" + publishDate);
    }

    public String contentHash(String title, String summary, String content) {
        return sha256Hex(safe(title) + "|" + safe(summary) + "|" + safe(content));
    }

    public String canonicalUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.isBlank()) {
                return rawUrl.trim().replaceAll("/+$", "");
            }
            int port = uri.getPort();
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            path = path.replaceAll("/+$", "");
            if (path.isBlank()) {
                path = "/";
            }

            String query = canonicalQuery(uri.getRawQuery());
            StringBuilder out = new StringBuilder();
            out.append(scheme).append("://").append(host);
            if (port > 0 && !((port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme)))) {
                out.append(":").append(port);
            }
            out.append(path);
            if (!query.isBlank()) {
                out.append("?").append(query);
            }
            return out.toString();
        } catch (Exception ignored) {
            return rawUrl.trim().replaceAll("/+$", "");
        }
    }

    public String normalizedTitle(String rawTitle) {
        if (rawTitle == null) {
            return "";
        }
        return rawTitle.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "")
                .replaceAll("[\\p{Punct}，。、“”‘’：；？！《》（）【】]", "");
    }

    private String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        List<String[]> pairs = new ArrayList<>();
        for (String part : rawQuery.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String[] kv = part.split("=", 2);
            String key = decode(kv[0]).toLowerCase(Locale.ROOT);
            if (TRACKING_PARAMS.contains(key)) {
                continue;
            }
            String value = kv.length > 1 ? decode(kv[1]) : "";
            pairs.add(new String[]{key, value});
        }
        pairs.sort(Comparator.comparing((String[] item) -> item[0]).thenComparing(item -> item[1]));
        List<String> encoded = new ArrayList<>();
        for (String[] pair : pairs) {
            encoded.add(encode(pair[0]) + (pair[1].isBlank() ? "" : "=" + encode(pair[1])));
        }
        return String.join("&", encoded);
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : bytes) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 not available", error);
        }
    }
}
