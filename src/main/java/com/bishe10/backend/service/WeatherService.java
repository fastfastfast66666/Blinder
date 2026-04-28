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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WeatherService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WeatherService.class);
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long CACHE_TTL_MINUTES = 10;
    private static final long FAILED_CACHE_TTL_SECONDS = 30;
    private static final double DEFAULT_LAT = 31.2304;
    private static final double DEFAULT_LNG = 121.4737;
    private static final DateTimeFormatter WEATHER_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, GeoProfile> CITY_GEO_PROFILES = Map.ofEntries(
            Map.entry("上海", new GeoProfile("上海", "上海", 31.2304, 121.4737)),
            Map.entry("北京", new GeoProfile("北京", "北京", 39.9042, 116.4074)),
            Map.entry("广州", new GeoProfile("广州", "广东", 23.1291, 113.2644)),
            Map.entry("深圳", new GeoProfile("深圳", "广东", 22.5431, 114.0579)),
            Map.entry("杭州", new GeoProfile("杭州", "浙江", 30.2741, 120.1551)),
            Map.entry("南京", new GeoProfile("南京", "江苏", 32.0603, 118.7969)),
            Map.entry("成都", new GeoProfile("成都", "四川", 30.5728, 104.0668)),
            Map.entry("重庆", new GeoProfile("重庆", "重庆", 29.5630, 106.5516)),
            Map.entry("武汉", new GeoProfile("武汉", "湖北", 30.5928, 114.3055)),
            Map.entry("西安", new GeoProfile("西安", "陕西", 34.3416, 108.9398)),
            Map.entry("苏州", new GeoProfile("苏州", "江苏", 31.2989, 120.5853)),
            Map.entry("天津", new GeoProfile("天津", "天津", 39.3434, 117.3616)),
            Map.entry("厦门", new GeoProfile("厦门", "福建", 24.4798, 118.0894)),
            Map.entry("青岛", new GeoProfile("青岛", "山东", 36.0671, 120.3826)),
            Map.entry("长沙", new GeoProfile("长沙", "湖南", 28.2282, 112.9388)),
            Map.entry("沈阳", new GeoProfile("沈阳", "辽宁", 41.8057, 123.4315)),
            Map.entry("大连", new GeoProfile("大连", "辽宁", 38.9140, 121.6147)),
            Map.entry("济南", new GeoProfile("济南", "山东", 36.6512, 117.1201)),
            Map.entry("郑州", new GeoProfile("郑州", "河南", 34.7466, 113.6254)),
            Map.entry("合肥", new GeoProfile("合肥", "安徽", 31.8206, 117.2272))
    );

    private final Bishe10Properties.Weather properties;
    private final ObjectMapper objectMapper;
    private final LlmService llmService;
    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public WeatherService(Bishe10Properties bishe10Properties, ObjectMapper objectMapper, LlmService llmService) {
        this.properties = bishe10Properties.getWeather();
        this.objectMapper = objectMapper;
        this.llmService = llmService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(weatherRequestTimeout())
                .build();
    }

    public Map<String, Object> buildLocalWeather(Double latitude, Double longitude, String city) {
        return buildLocalWeather(latitude, longitude, city, false);
    }

    public Map<String, Object> buildLocalWeather(Double latitude, Double longitude, String city, boolean force) {
        RequestLocation requestLocation = resolveRequestLocation(latitude, longitude, city);
        String cacheKey = requestLocation.cacheKey();
        OffsetDateTime now = OffsetDateTime.now();

        if (force) {
            cache.remove(cacheKey);
        }

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(now)) {
            return copyPayload(cached.payload());
        }

        Map<String, Object> payload;
        try {
            payload = fetchStructuredWeather(requestLocation);
        } catch (Exception error) {
            LOGGER.warn("Weather request failed for city={}", requestLocation.city(), error);
            payload = buildFallbackWeather(requestLocation);
        }

        boolean available = Boolean.TRUE.equals(payload.get("available"));
        OffsetDateTime expiresAt = available
                ? now.plusMinutes(CACHE_TTL_MINUTES)
                : now.plusSeconds(FAILED_CACHE_TTL_SECONDS);
        cache.put(cacheKey, new CacheEntry(copyPayload(payload), expiresAt));
        return payload;
    }

    private RequestLocation resolveRequestLocation(Double latitude, Double longitude, String city) {
        String normalizedCity = normalizeCityName(city);
        Optional<GeoProfile> cityProfile = localCityProfile(normalizedCity);
        if (cityProfile.isEmpty() && !isValidCoordinate(latitude, longitude)) {
            cityProfile = lookupCityProfile(normalizedCity);
        }

        if (isValidCoordinate(latitude, longitude)) {
            GeoProfile profile = cityProfile.orElse(null);
            String resolvedCity = !normalizedCity.isBlank()
                    ? normalizedCity
                    : profile == null ? "当前城市" : profile.city();
            String province = profile == null ? "" : profile.province();
            return new RequestLocation(resolvedCity, province, latitude, longitude);
        }

        if (cityProfile.isPresent()) {
            GeoProfile profile = cityProfile.get();
            return new RequestLocation(profile.city(), profile.province(), profile.latitude(), profile.longitude());
        }

        String fallbackCity = normalizedCity.isBlank() ? "上海" : normalizedCity;
        return new RequestLocation(fallbackCity, "", DEFAULT_LAT, DEFAULT_LNG);
    }

    private Optional<GeoProfile> localCityProfile(String city) {
        if (city == null || city.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalizeCityName(city);
        GeoProfile exact = CITY_GEO_PROFILES.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (Map.Entry<String, GeoProfile> entry : CITY_GEO_PROFILES.entrySet()) {
            if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> fetchStructuredWeather(RequestLocation requestLocation) throws IOException, InterruptedException {
        if (!properties.isEnabled()) {
            return buildFallbackWeather(requestLocation);
        }

        String weatherUrl = "%s?latitude=%s&longitude=%s&current=weather_code,temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,visibility,pressure_msl,wind_speed_10m,wind_direction_10m&timezone=Asia%%2FShanghai"
                .formatted(
                        normalizeBaseUrl(properties.getBaseUrl()),
                        formatCoordinate(requestLocation.latitude()),
                        formatCoordinate(requestLocation.longitude())
                );
        String airQualityUrl = "%s?latitude=%s&longitude=%s&current=us_aqi&timezone=Asia%%2FShanghai"
                .formatted(
                        normalizeBaseUrl(properties.getAirQualityBaseUrl()),
                        formatCoordinate(requestLocation.latitude()),
                        formatCoordinate(requestLocation.longitude())
                );

        JsonNode weatherRoot = sendJson(weatherUrl);
        JsonNode current = weatherRoot.path("current");
        if (current.isMissingNode() || current.isNull()) {
            throw new IOException("Weather payload missing current section");
        }

        JsonNode airCurrent = null;
        try {
            JsonNode airQualityRoot = sendJson(airQualityUrl);
            airCurrent = airQualityRoot.path("current");
        } catch (Exception error) {
            LOGGER.warn("Weather air quality request failed for city={}", requestLocation.city(), error);
        }

        int weatherCode = current.path("weather_code").asInt(-1);
        int temperature = roundedInt(current.path("temperature_2m"));
        int feelsLike = roundedInt(current.path("apparent_temperature"));
        int humidity = roundedInt(current.path("relative_humidity_2m"));
        double precipitation = roundedOneDecimal(current.path("precipitation").asDouble(0.0));
        double visibilityKm = roundedOneDecimal(current.path("visibility").asDouble(0.0) / 1000.0);
        int pressure = roundedInt(current.path("pressure_msl"));
        int windSpeed = roundedInt(current.path("wind_speed_10m"));
        int windDirectionDegree = roundedInt(current.path("wind_direction_10m"));
        int usAqi = airCurrent == null ? -1 : airCurrent.path("us_aqi").asInt(-1);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", true);
        payload.put("provider", properties.getProviderLabel());
        payload.put("location", requestLocation.city());
        payload.put("province", requestLocation.province());
        payload.put("lat", roundedCoordinate(requestLocation.latitude()));
        payload.put("lng", roundedCoordinate(requestLocation.longitude()));
        payload.put("weather_text", weatherText(weatherCode));
        payload.put("temperature", temperature);
        payload.put("feels_like", feelsLike);
        payload.put("humidity", humidity);
        payload.put("wind_speed", windSpeed);
        payload.put("wind_direction", windDirectionText(windDirectionDegree));
        payload.put("precipitation", precipitation);
        payload.put("visibility", visibilityKm);
        payload.put("pressure", pressure);
        payload.put("air_quality", airQualityLabel(usAqi));
        payload.put("alert", deriveAlert(weatherCode, precipitation, visibilityKm, windSpeed, temperature));
        payload.put("update_time", normalizeUpdateTime(current.path("time").asText("")));

        Map<String, String> advice = buildTravelAdvice(payload);
        payload.put("summary", advice.get("summary"));
        payload.put("travel_advice", advice.get("travelAdvice"));
        payload.put("spoken_text", advice.get("spokenText"));
        return payload;
    }

    private Map<String, String> buildTravelAdvice(Map<String, Object> payload) {
        String fallbackSummary = buildFallbackSummary(payload);
        String fallbackAdvice = buildFallbackAdvice(payload);
        String fallbackSpokenText = buildFallbackSpokenText(payload, fallbackAdvice);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("summary", fallbackSummary);
        result.put("travelAdvice", fallbackAdvice);
        result.put("spokenText", fallbackSpokenText);
        return result;
    }

    private String buildFallbackSummary(Map<String, Object> payload) {
        String weatherText = asText(payload.get("weather_text"));
        String alert = asText(payload.get("alert"));
        int temperature = intValue(payload.get("temperature"));
        String summary = "%s %s℃".formatted(weatherText, temperature);
        if (!alert.isBlank() && !"当前无特别预警".equals(alert)) {
            summary += " · " + alert;
        }
        return summary;
    }

    private String buildFallbackAdvice(Map<String, Object> payload) {
        int temperature = intValue(payload.get("temperature"));
        int feelsLike = intValue(payload.get("feels_like"));
        int windSpeed = intValue(payload.get("wind_speed"));
        double precipitation = doubleValue(payload.get("precipitation"));
        double visibility = doubleValue(payload.get("visibility"));
        String alert = asText(payload.get("alert"));

        if (!alert.isBlank() && !"当前无特别预警".equals(alert)) {
            return "当前有天气风险，建议优先减少非必要外出；如需出门，请走熟悉路线，提前确认雨具、遮挡和可避雨停留点。";
        }
        if (precipitation >= 8) {
            return "路面可能持续湿滑，建议放慢速度，优先沿连续盲道和固定路线通行，过路口前先停步确认车流与积水。";
        }
        if (visibility > 0 && visibility <= 3) {
            return "当前能见度偏低，建议尽量结伴出行，减少复杂路口切换；需要过街时先停步确认语音信号和车辆动静。";
        }
        if (windSpeed >= 28) {
            return "风力较明显，建议避开广告牌、围挡和临时施工区，手持雨伞或手机时注意保持另一只手稳定探路。";
        }
        if (temperature >= 32 || feelsLike >= 34) {
            return "体感偏热，建议缩短连续步行时间，优先选择有遮挡的路线，并随身补水，避免长时间停留在暴晒区域。";
        }
        if (temperature <= 5 || feelsLike <= 3) {
            return "气温偏低，建议外出前先确认保暖和手机电量，过路口时放慢速度，避免因手部僵硬影响探路动作。";
        }
        return "当前天气相对平稳，适合短距离外出；仍建议优先走熟悉路线，出门前先确认常用电梯、盲道和路口语音提示是否正常。";
    }

    private String buildFallbackSpokenText(Map<String, Object> payload, String advice) {
        String location = asText(payload.get("location"));
        String weatherText = asText(payload.get("weather_text"));
        int temperature = intValue(payload.get("temperature"));
        String alert = asText(payload.get("alert"));

        StringBuilder builder = new StringBuilder();
        builder.append(location)
                .append("当前")
                .append(weatherText)
                .append("，")
                .append(temperature)
                .append("度。");
        if (!alert.isBlank() && !"当前无特别预警".equals(alert)) {
            builder.append(alert).append("。");
        }
        builder.append(advice);
        return builder.toString();
    }

    private Map<String, Object> buildFallbackWeather(RequestLocation requestLocation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("available", false);
        payload.put("provider", properties.getProviderLabel());
        payload.put("location", requestLocation.city());
        payload.put("province", requestLocation.province());
        payload.put("lat", roundedCoordinate(requestLocation.latitude()));
        payload.put("lng", roundedCoordinate(requestLocation.longitude()));
        payload.put("weather_text", "天气待确认");
        payload.put("temperature", 0);
        payload.put("feels_like", 0);
        payload.put("humidity", 0);
        payload.put("wind_speed", 0);
        payload.put("wind_direction", "风向待确认");
        payload.put("precipitation", 0.0);
        payload.put("visibility", 0.0);
        payload.put("pressure", 0);
        payload.put("air_quality", "待确认");
        payload.put("alert", "天气接口暂不可用");
        payload.put("update_time", LocalDateTime.now(SHANGHAI_ZONE).format(WEATHER_TIME));
        payload.put("summary", "天气接口暂不可用");
        payload.put("travel_advice", "暂时无法获取实时天气，建议出门前先确认窗外降雨、路面湿滑情况和常用路线，再决定是否外出。");
        payload.put("spoken_text", requestLocation.city() + "天气接口暂不可用，建议先确认窗外天气和常用路线，再决定是否外出。");
        return payload;
    }

    private JsonNode sendJson(String url) throws IOException, InterruptedException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return sendJsonOnce(url);
            } catch (IOException error) {
                lastError = error;
                if (attempt == 2) {
                    break;
                }
                LOGGER.info("Weather provider request failed, retrying attempt={} url={}", attempt, url, error);
            }
        }
        throw lastError;
    }

    private JsonNode sendJsonOnce(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(weatherRequestTimeout())
                .header("accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Weather provider returned status " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private Duration weatherRequestTimeout() {
        return Duration.ofSeconds(Math.max(1, Math.min(30, properties.getTimeoutSeconds())));
    }

    private Optional<GeoProfile> lookupCityProfile(String city) {
        if (!properties.isEnabled() || city == null || city.isBlank()) {
            return Optional.empty();
        }

        try {
            String url = "%s?name=%s&count=1&language=zh&format=json"
                    .formatted(
                            normalizeBaseUrl(properties.getGeocodeBaseUrl()),
                            URLEncoder.encode(city, StandardCharsets.UTF_8)
                    );
            JsonNode root = sendJson(url);
            JsonNode first = root.path("results").path(0);
            if (first.isMissingNode() || first.isNull()) {
                return Optional.empty();
            }

            String name = normalizeCityName(first.path("name").asText(city));
            String province = first.path("admin1").asText("");
            double latitude = first.path("latitude").asDouble(Double.NaN);
            double longitude = first.path("longitude").asDouble(Double.NaN);
            if (!isValidCoordinate(latitude, longitude)) {
                return Optional.empty();
            }
            return Optional.of(new GeoProfile(name.isBlank() ? city : name, province, latitude, longitude));
        } catch (Exception error) {
            LOGGER.debug("Weather geocode lookup failed for city={}", city, error);
            return Optional.empty();
        }
    }

    private String weatherText(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "晴间多云";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55, 56, 57 -> "毛毛雨";
            case 61, 63 -> "小雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71, 73 -> "小雪";
            case 75, 77 -> "大雪";
            case 80, 81 -> "阵雨";
            case 82 -> "暴雨";
            case 85, 86 -> "阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "强雷暴";
            default -> "天气待确认";
        };
    }

    private String deriveAlert(int weatherCode, double precipitation, double visibilityKm, int windSpeed, int temperature) {
        if (weatherCode == 95 || weatherCode == 96 || weatherCode == 99) {
            return "雷暴出行提醒";
        }
        if (weatherCode == 82 || precipitation >= 20) {
            return "强降雨出行提醒";
        }
        if (weatherCode == 65 || precipitation >= 8) {
            return "降雨路滑提醒";
        }
        if (visibilityKm > 0 && visibilityKm <= 2) {
            return "低能见度提醒";
        }
        if (windSpeed >= 39) {
            return "大风提醒";
        }
        if (temperature >= 35) {
            return "高温提醒";
        }
        if (temperature <= 0) {
            return "低温结冰提醒";
        }
        return "当前无特别预警";
    }

    private String airQualityLabel(int aqi) {
        if (aqi < 0) return "待确认";
        if (aqi <= 50) return "优";
        if (aqi <= 100) return "良";
        if (aqi <= 150) return "轻度污染";
        if (aqi <= 200) return "中度污染";
        if (aqi <= 300) return "重度污染";
        return "严重污染";
    }

    private String windDirectionText(int degree) {
        if (degree < 0) return "风向待确认";
        String[] directions = {
                "北风", "东北风", "东北风", "东风",
                "东风", "东南风", "东南风", "南风",
                "南风", "西南风", "西南风", "西风",
                "西风", "西北风", "西北风", "北风"
        };
        int index = (int) Math.round(((degree % 360) / 22.5)) % 16;
        return directions[index];
    }

    private String normalizeUpdateTime(String rawTime) {
        if (rawTime == null || rawTime.isBlank()) {
            return LocalDateTime.now(SHANGHAI_ZONE).format(WEATHER_TIME);
        }
        try {
            return LocalDateTime.parse(rawTime).format(WEATHER_TIME);
        } catch (DateTimeParseException ignore) {
            return rawTime.replace('T', ' ') + (rawTime.length() == 16 ? ":00" : "");
        }
    }

    private String normalizeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String normalizeCityName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String normalized = raw.trim();
        String[] suffixes = {"特别行政区", "自治州", "自治区", "市辖区", "地区", "盟", "省", "市", "县"};
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String suffix : suffixes) {
                if (normalized.endsWith(suffix)) {
                    normalized = normalized.substring(0, normalized.length() - suffix.length()).trim();
                    changed = true;
                    break;
                }
            }
        }
        return normalized;
    }

    private boolean isValidCoordinate(Double latitude, Double longitude) {
        return latitude != null
                && longitude != null
                && !Double.isNaN(latitude)
                && !Double.isNaN(longitude)
                && latitude >= -90
                && latitude <= 90
                && longitude >= -180
                && longitude <= 180;
    }

    private int roundedInt(JsonNode node) {
        return (int) Math.round(node.asDouble(0.0));
    }

    private int roundedInt(Object value) {
        return (int) Math.round(doubleValue(value));
    }

    private double roundedOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double roundedCoordinate(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private String formatCoordinate(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private String asText(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return (int) Math.round(Double.parseDouble(text));
            } catch (NumberFormatException ignore) {
                return 0;
            }
        }
        return 0;
    }

    private double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignore) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private Map<String, Object> copyPayload(Map<String, Object> payload) {
        return new LinkedHashMap<>(payload);
    }

    private record CacheEntry(
            Map<String, Object> payload,
            OffsetDateTime expiresAt
    ) {
    }

    private record RequestLocation(
            String city,
            String province,
            double latitude,
            double longitude
    ) {
        private String cacheKey() {
            return city + "@" + Math.round(latitude * 100) + ":" + Math.round(longitude * 100);
        }
    }

    private record GeoProfile(
            String city,
            String province,
            double latitude,
            double longitude
    ) {
    }
}
