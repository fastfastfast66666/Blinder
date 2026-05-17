package com.bishe10.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bishe10")
public class Bishe10Properties {

    private final Storage storage = new Storage();
    private final Auth auth = new Auth();
    private final Admin admin = new Admin();
    private final Llm llm = new Llm();
    private final Tts tts = new Tts();
    private final Asr asr = new Asr();
    private final Location location = new Location();
    private final News news = new News();
    private final Weather weather = new Weather();

    public Storage getStorage() {
        return storage;
    }

    public Auth getAuth() {
        return auth;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Llm getLlm() {
        return llm;
    }

    public Tts getTts() {
        return tts;
    }

    public Asr getAsr() {
        return asr;
    }

    public Location getLocation() {
        return location;
    }

    public News getNews() {
        return news;
    }

    public Weather getWeather() {
        return weather;
    }

    public static class Storage {
        private String rootDir = "runtime-data";

        public String getRootDir() {
            return rootDir;
        }

        public void setRootDir(String rootDir) {
            this.rootDir = rootDir;
        }
    }

    public static class Auth {
        private final Database db = new Database();
        private final Mail mail = new Mail();
        private int codeTtlSeconds = 300;
        private int codeResendCooldownSeconds = 45;
        private int tokenTtlDays = 30;
        private String codePepper = "bishe10-local-code-pepper";

        public Database getDb() {
            return db;
        }

        public Mail getMail() {
            return mail;
        }

        public int getCodeTtlSeconds() {
            return codeTtlSeconds;
        }

        public void setCodeTtlSeconds(int codeTtlSeconds) {
            this.codeTtlSeconds = codeTtlSeconds;
        }

        public int getCodeResendCooldownSeconds() {
            return codeResendCooldownSeconds;
        }

        public void setCodeResendCooldownSeconds(int codeResendCooldownSeconds) {
            this.codeResendCooldownSeconds = codeResendCooldownSeconds;
        }

        public int getTokenTtlDays() {
            return tokenTtlDays;
        }

        public void setTokenTtlDays(int tokenTtlDays) {
            this.tokenTtlDays = tokenTtlDays;
        }

        public String getCodePepper() {
            return codePepper;
        }

        public void setCodePepper(String codePepper) {
            this.codePepper = codePepper;
        }

        public static class Database {
            private String url = "";
            private String username = "";
            private String password = "";

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }
        }

        public static class Mail {
            private boolean enabled = true;
            private String host = "smtp.qq.com";
            private int port = 465;
            private String username = "";
            private String password = "";
            private String from = "";
            private String senderName = "Bishe10";
            private boolean sslEnabled = true;
            private int timeoutMs = 10000;

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public String getHost() {
                return host;
            }

            public void setHost(String host) {
                this.host = host;
            }

            public int getPort() {
                return port;
            }

            public void setPort(int port) {
                this.port = port;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }

            public String getFrom() {
                return from;
            }

            public void setFrom(String from) {
                this.from = from;
            }

            public String getSenderName() {
                return senderName;
            }

            public void setSenderName(String senderName) {
                this.senderName = senderName;
            }

            public boolean isSslEnabled() {
                return sslEnabled;
            }

            public void setSslEnabled(boolean sslEnabled) {
                this.sslEnabled = sslEnabled;
            }

            public int getTimeoutMs() {
                return timeoutMs;
            }

            public void setTimeoutMs(int timeoutMs) {
                this.timeoutMs = timeoutMs;
            }

            public String resolvedFrom() {
                return from == null || from.isBlank() ? username : from;
            }
        }
    }

    public static class Admin {
        private String defaultUsername = "admin";
        private String defaultPassword = "admin123";
        private String defaultNickname = "系统管理员";
        private int tokenTtlHours = 12;

        public String getDefaultUsername() {
            return defaultUsername;
        }

        public void setDefaultUsername(String defaultUsername) {
            this.defaultUsername = defaultUsername;
        }

        public String getDefaultPassword() {
            return defaultPassword;
        }

        public void setDefaultPassword(String defaultPassword) {
            this.defaultPassword = defaultPassword;
        }

        public String getDefaultNickname() {
            return defaultNickname;
        }

        public void setDefaultNickname(String defaultNickname) {
            this.defaultNickname = defaultNickname;
        }

        public int getTokenTtlHours() {
            return tokenTtlHours;
        }

        public void setTokenTtlHours(int tokenTtlHours) {
            this.tokenTtlHours = tokenTtlHours;
        }
    }

    public static class Llm {
        private boolean enabled = true;
        private String baseUrl = "http://127.0.0.1:3013/v1";
        private String apiKey = "local-thalamus";
        private String model = "fast";
        private String providerLabel = "Gemini Flash";
        private int timeoutSeconds = 25;
        private String visionBaseUrl = "";
        private String visionApiKey = "";
        private String visionModel = "";
        private String visionProviderLabel = "";
        private int visionTimeoutSeconds = 0;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getProviderLabel() {
            return providerLabel;
        }

        public void setProviderLabel(String providerLabel) {
            this.providerLabel = providerLabel;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getVisionBaseUrl() {
            return visionBaseUrl;
        }

        public void setVisionBaseUrl(String visionBaseUrl) {
            this.visionBaseUrl = visionBaseUrl == null ? "" : visionBaseUrl;
        }

        public String getVisionApiKey() {
            return visionApiKey;
        }

        public void setVisionApiKey(String visionApiKey) {
            this.visionApiKey = visionApiKey == null ? "" : visionApiKey;
        }

        public String getVisionModel() {
            return visionModel;
        }

        public void setVisionModel(String visionModel) {
            this.visionModel = visionModel == null ? "" : visionModel;
        }

        public String getVisionProviderLabel() {
            return visionProviderLabel;
        }

        public void setVisionProviderLabel(String visionProviderLabel) {
            this.visionProviderLabel = visionProviderLabel == null ? "" : visionProviderLabel;
        }

        public int getVisionTimeoutSeconds() {
            return visionTimeoutSeconds;
        }

        public void setVisionTimeoutSeconds(int visionTimeoutSeconds) {
            this.visionTimeoutSeconds = visionTimeoutSeconds;
        }

        public String resolvedVisionBaseUrl() {
            return visionBaseUrl == null || visionBaseUrl.isBlank() ? baseUrl : visionBaseUrl;
        }

        public String resolvedVisionApiKey() {
            return visionApiKey == null || visionApiKey.isBlank() ? apiKey : visionApiKey;
        }

        public String resolvedVisionModel() {
            return visionModel == null || visionModel.isBlank() ? model : visionModel;
        }

        public String resolvedVisionProviderLabel() {
            return visionProviderLabel == null || visionProviderLabel.isBlank() ? providerLabel : visionProviderLabel;
        }

        public int resolvedVisionTimeoutSeconds() {
            return visionTimeoutSeconds <= 0 ? timeoutSeconds : visionTimeoutSeconds;
        }
    }

    public static class Tts {
        private boolean enabled = true;
        private String pythonBin = "python3";
        private String scriptPath = "scripts/tts_edge.py";
        private String voice = "zh-CN-XiaoxiaoNeural";
        private String providerLabel = "Edge TTS";
        private int timeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPythonBin() {
            return pythonBin;
        }

        public void setPythonBin(String pythonBin) {
            this.pythonBin = pythonBin;
        }

        public String getScriptPath() {
            return scriptPath;
        }

        public void setScriptPath(String scriptPath) {
            this.scriptPath = scriptPath;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public String getProviderLabel() {
            return providerLabel;
        }

        public void setProviderLabel(String providerLabel) {
            this.providerLabel = providerLabel;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Asr {
        private boolean enabled = true;
        private String providerLabel = "Tencent Cloud ASR";
        private String baseUrl = "https://asr.tencentcloudapi.com";
        private String secretId = "";
        private String secretKey = "";
        private String region = "ap-shanghai";
        private String engineModelType = "16k_zh";
        private String voiceFormat = "aac";
        private int timeoutSeconds = 30;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProviderLabel() {
            return providerLabel;
        }

        public void setProviderLabel(String providerLabel) {
            this.providerLabel = providerLabel;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSecretId() {
            return secretId;
        }

        public void setSecretId(String secretId) {
            this.secretId = secretId == null ? "" : secretId;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey == null ? "" : secretKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region == null ? "" : region;
        }

        public String getEngineModelType() {
            return engineModelType;
        }

        public void setEngineModelType(String engineModelType) {
            this.engineModelType = engineModelType == null ? "" : engineModelType;
        }

        public String getVoiceFormat() {
            return voiceFormat;
        }

        public void setVoiceFormat(String voiceFormat) {
            this.voiceFormat = voiceFormat == null ? "" : voiceFormat;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Location {
        private boolean enabled = true;
        private String providerLabel = "Tencent Maps";
        private String baseUrl = "https://apis.map.qq.com/ws/geocoder/v1";
        private String apiKey = "";
        private String defaultCity = "上海";
        private int timeoutSeconds = 6;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProviderLabel() {
            return providerLabel;
        }

        public void setProviderLabel(String providerLabel) {
            this.providerLabel = providerLabel;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getDefaultCity() {
            return defaultCity;
        }

        public void setDefaultCity(String defaultCity) {
            this.defaultCity = defaultCity;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class News {
        private boolean enabled = true;
        private String providerLabel = "Baidu News Search";
        private String baseUrl = "https://www.baidu.com/s";
        private String apiKey = "";
        private int timeoutSeconds = 8;
        private int maxPerQuery = 12;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProviderLabel() {
            return providerLabel;
        }

        public void setProviderLabel(String providerLabel) {
            this.providerLabel = providerLabel;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxPerQuery() {
            return maxPerQuery;
        }

        public void setMaxPerQuery(int maxPerQuery) {
            this.maxPerQuery = maxPerQuery;
        }
    }

    public static class Weather {
        private boolean enabled = true;
        private String providerLabel = "Open-Meteo";
        private String baseUrl = "https://api.open-meteo.com/v1/forecast";
        private String airQualityBaseUrl = "https://air-quality-api.open-meteo.com/v1/air-quality";
        private String geocodeBaseUrl = "https://geocoding-api.open-meteo.com/v1/search";
        private int timeoutSeconds = 8;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProviderLabel() {
            return providerLabel;
        }

        public void setProviderLabel(String providerLabel) {
            this.providerLabel = providerLabel;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getAirQualityBaseUrl() {
            return airQualityBaseUrl;
        }

        public void setAirQualityBaseUrl(String airQualityBaseUrl) {
            this.airQualityBaseUrl = airQualityBaseUrl;
        }

        public String getGeocodeBaseUrl() {
            return geocodeBaseUrl;
        }

        public void setGeocodeBaseUrl(String geocodeBaseUrl) {
            this.geocodeBaseUrl = geocodeBaseUrl;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
