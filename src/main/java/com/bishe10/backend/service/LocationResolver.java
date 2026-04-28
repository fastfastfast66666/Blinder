package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class LocationResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationResolver.class);
    private static final double MAX_APPROXIMATE_CITY_DISTANCE = 3.0;

    private static final List<ApproximateCity> APPROXIMATE_CITIES = List.of(
            city("上海", 31.2304, 121.4737),
            city("北京", 39.9042, 116.4074),
            city("广州", 23.1291, 113.2644),
            city("深圳", 22.5431, 114.0579),
            city("杭州", 30.2741, 120.1551),
            city("镇江", 32.1901, 119.4200),
            city("武汉", 30.5928, 114.3055),
            city("成都", 30.5728, 104.0668),
            city("重庆", 29.5630, 106.5516),
            city("苏州", 31.3016, 120.5810),
            city("天津", 39.0842, 117.2009)
    );

    private final Bishe10Properties.Location properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LocationResolver(Bishe10Properties bishe10Properties, ObjectMapper objectMapper) {
        this.properties = bishe10Properties.getLocation();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    public ResolvedLocation resolve(Double latitude, Double longitude, String city) {
        OffsetDateTime now = OffsetDateTime.now();

        if (isValidCoordinate(latitude, longitude)) {
            String resolvedCity = reverseGeocode(latitude, longitude);
            if (resolvedCity != null) {
                return new ResolvedLocation(
                        normalizeCityName(resolvedCity),
                        "gps",
                        "granted",
                        now.toString()
                );
            }

            ApproximateCity approximateCity = findNearestCity(latitude, longitude);
            if (approximateCity != null) {
                return new ResolvedLocation(
                        approximateCity.name(),
                        "gps",
                        "granted",
                        now.toString()
                );
            }
        }

        if (city != null && !city.isBlank()) {
            return new ResolvedLocation(
                    normalizeCityName(city),
                    "manual",
                    "unknown",
                    now.toString()
            );
        }

        return new ResolvedLocation(
                normalizeCityName(properties.getDefaultCity()),
                "default",
                "unknown",
                now.toString()
        );
    }

    private String reverseGeocode(Double latitude, Double longitude) {
        if (!properties.isEnabled() || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return null;
        }

        try {
            String query = "key=%s&location=%s,%s&get_poi=0".formatted(
                    URLEncoder.encode(properties.getApiKey(), StandardCharsets.UTF_8),
                    latitude,
                    longitude
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(properties.getBaseUrl()) + "?" + query))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("Location resolver request failed with status {}", response.statusCode());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            int status = root.path("status").asInt(-1);
            if (status != 0) {
                LOGGER.warn("Location resolver returned non-zero status {}", status);
                return null;
            }

            JsonNode component = root.path("result").path("address_component");
            String city = component.path("city").asText("");
            if (!city.isBlank()) {
                return city;
            }

            String province = component.path("province").asText("");
            if (!province.isBlank()) {
                return province;
            }
            return null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Location resolver interrupted", error);
            return null;
        } catch (IOException error) {
            LOGGER.warn("Location resolver returned invalid payload", error);
            return null;
        } catch (Exception error) {
            LOGGER.warn("Location resolver failed", error);
            return null;
        }
    }

    private boolean isValidCoordinate(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    private ApproximateCity findNearestCity(Double latitude, Double longitude) {
        ApproximateCity bestMatch = null;
        double bestDistance = Double.MAX_VALUE;

        for (ApproximateCity item : APPROXIMATE_CITIES) {
            double distance = squaredDistance(latitude, longitude, item.latitude(), item.longitude());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = item;
            }
        }

        if (bestMatch == null || bestDistance > MAX_APPROXIMATE_CITY_DISTANCE * MAX_APPROXIMATE_CITY_DISTANCE) {
            LOGGER.info(
                    "Coordinate is too far from supported cities, skip approximate city match latitude={} longitude={}",
                    latitude,
                    longitude
            );
            return null;
        }

        return bestMatch;
    }

    private double squaredDistance(double lat1, double lng1, double lat2, double lng2) {
        double latDelta = lat1 - lat2;
        double lngDelta = lng1 - lng2;
        return latDelta * latDelta + lngDelta * lngDelta;
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String normalizeCityName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "上海";
        }

        String normalized = raw.trim()
                .replace("特别行政区", "")
                .replace("自治州", "")
                .replace("自治区", "")
                .replace("地区", "")
                .replace("市辖区", "")
                .replace("市", "")
                .replace("省", "");

        if (normalized.isBlank()) {
            return "上海";
        }
        return normalized;
    }

    private static ApproximateCity city(String name, double latitude, double longitude) {
        return new ApproximateCity(name, latitude, longitude);
    }

    public record ResolvedLocation(
            String city,
            String source,
            String permission,
            String updatedAt
    ) {
    }

    private record ApproximateCity(
            String name,
            double latitude,
            double longitude
    ) {
    }
}
