package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.model.NewsSourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class NewsSourceConfigRepository extends JdbcRepositorySupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(NewsSourceConfigRepository.class);

    public NewsSourceConfigRepository(Bishe10Properties properties) {
        super(properties);
    }

    public void initializeDefaults() throws SQLException {
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            for (DefaultSource source : defaultSources()) {
                upsertDefault(connection, source, false);
            }
        }
    }

    public void resetToDefaults() throws SQLException {
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            for (DefaultSource source : defaultSources()) {
                upsertDefault(connection, source, true);
            }
        }
    }

    public List<NewsSourceConfig> findAll() throws SQLException {
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            List<NewsSourceConfig> items = new ArrayList<>();
            String sql = """
                    SELECT source_key, source_name, source_type, endpoint, enabled, priority,
                           description, last_fetch_at, last_status, last_message, created_at, updated_at
                    FROM news_source_config
                    ORDER BY priority ASC, source_key ASC
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(readSource(rs));
                }
            }
            return items;
        }
    }

    public List<NewsSourceConfig> findEnabled() throws SQLException {
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            List<NewsSourceConfig> items = new ArrayList<>();
            String sql = """
                    SELECT source_key, source_name, source_type, endpoint, enabled, priority,
                           description, last_fetch_at, last_status, last_message, created_at, updated_at
                    FROM news_source_config
                    WHERE enabled = 1
                    ORDER BY priority ASC, source_key ASC
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(readSource(rs));
                }
            }
            return items;
        }
    }

    public Optional<NewsSourceConfig> findByKey(String sourceKey) throws SQLException {
        if (sourceKey == null || sourceKey.isBlank()) {
            return Optional.empty();
        }
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            String sql = """
                    SELECT source_key, source_name, source_type, endpoint, enabled, priority,
                           description, last_fetch_at, last_status, last_message, created_at, updated_at
                    FROM news_source_config
                    WHERE source_key = ?
                    LIMIT 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, normalizeKey(sourceKey));
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(readSource(rs));
                    }
                }
            }
        }
        return Optional.empty();
    }

    public NewsSourceConfig update(String sourceKey, Boolean enabled, Integer priority, String description) throws SQLException {
        String key = normalizeKey(sourceKey);
        if (key.isBlank()) {
            throw new IllegalArgumentException("新闻源标识不能为空。");
        }
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            StringBuilder sql = new StringBuilder("UPDATE news_source_config SET updated_at = NOW(3)");
            List<Object> params = new ArrayList<>();
            if (enabled != null) {
                sql.append(", enabled = ?");
                params.add(enabled ? 1 : 0);
            }
            if (priority != null) {
                sql.append(", priority = ?");
                params.add(Math.max(1, priority));
            }
            if (description != null) {
                sql.append(", description = ?");
                params.add(description.trim());
            }
            sql.append(" WHERE source_key = ?");
            params.add(key);

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                bind(statement, params);
                int updated = statement.executeUpdate();
                if (updated == 0) {
                    throw new IllegalArgumentException("新闻源不存在：" + key);
                }
            }
            return findByKey(key).orElseThrow(() -> new IllegalArgumentException("新闻源不存在：" + key));
        }
    }

    public void recordFetch(String sourceKey, int itemCount, String status, String message) {
        String key = normalizeKey(sourceKey);
        if (key.isBlank()) {
            return;
        }
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            String sql = """
                    UPDATE news_source_config
                    SET last_fetch_at = NOW(3), last_status = ?, last_message = ?, updated_at = NOW(3)
                    WHERE source_key = ?
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, status == null || status.isBlank() ? (itemCount > 0 ? "OK" : "EMPTY") : status);
                statement.setString(2, trim(message, 512));
                statement.setString(3, key);
                statement.executeUpdate();
            }
        } catch (Exception error) {
            LOGGER.debug("record news source fetch status failed source={}", key, error);
        }
    }

    public List<NewsSourceConfig> defaultConfigs() {
        return defaultSources().stream()
                .map(source -> new NewsSourceConfig(
                        source.key(),
                        source.name(),
                        source.type(),
                        source.endpoint(),
                        source.enabled(),
                        source.priority(),
                        source.description(),
                        null,
                        "INIT",
                        "系统默认新闻源",
                        null,
                        null
                ))
                .toList();
    }

    private void ensureTable(Connection connection) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS news_source_config (
                  source_key VARCHAR(64) NOT NULL,
                  source_name VARCHAR(128) NOT NULL,
                  source_type VARCHAR(32) NOT NULL,
                  endpoint VARCHAR(1024) NULL,
                  enabled TINYINT(1) NOT NULL DEFAULT 1,
                  priority INT NOT NULL DEFAULT 100,
                  description VARCHAR(512) NULL,
                  last_fetch_at DATETIME(3) NULL,
                  last_status VARCHAR(32) NULL,
                  last_message VARCHAR(512) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (source_key),
                  KEY idx_news_source_enabled_priority (enabled, priority)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void upsertDefault(Connection connection, DefaultSource source, boolean resetEnabled) throws SQLException {
        String sql = resetEnabled ? """
                INSERT INTO news_source_config (
                  source_key, source_name, source_type, endpoint, enabled, priority,
                  description, last_status, last_message, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'INIT', '系统默认新闻源', NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE
                  source_name = VALUES(source_name),
                  source_type = VALUES(source_type),
                  endpoint = VALUES(endpoint),
                  enabled = VALUES(enabled),
                  priority = VALUES(priority),
                  description = VALUES(description),
                  updated_at = NOW(3)
                """
                : """
                INSERT INTO news_source_config (
                  source_key, source_name, source_type, endpoint, enabled, priority,
                  description, last_status, last_message, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'INIT', '系统默认新闻源', NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE
                  source_name = VALUES(source_name),
                  source_type = VALUES(source_type),
                  endpoint = VALUES(endpoint),
                  priority = VALUES(priority),
                  description = VALUES(description),
                  updated_at = NOW(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, source.key());
            statement.setString(2, source.name());
            statement.setString(3, source.type());
            statement.setString(4, source.endpoint());
            statement.setInt(5, source.enabled() ? 1 : 0);
            statement.setInt(6, source.priority());
            statement.setString(7, source.description());
            statement.executeUpdate();
        }
    }

    private NewsSourceConfig readSource(ResultSet rs) throws SQLException {
        OffsetDateTime lastFetchAt = toOffsetDateTime(rs.getTimestamp("last_fetch_at"));
        OffsetDateTime createdAt = toOffsetDateTime(rs.getTimestamp("created_at"));
        OffsetDateTime updatedAt = toOffsetDateTime(rs.getTimestamp("updated_at"));
        return new NewsSourceConfig(
                rs.getString("source_key"),
                rs.getString("source_name"),
                rs.getString("source_type"),
                rs.getString("endpoint"),
                rs.getInt("enabled") == 1,
                rs.getInt("priority"),
                rs.getString("description"),
                lastFetchAt,
                rs.getString("last_status"),
                rs.getString("last_message"),
                createdAt,
                updatedAt
        );
    }

    private void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Integer number) {
                statement.setInt(i + 1, number);
            } else {
                statement.setString(i + 1, value == null ? "" : String.valueOf(value));
            }
        }
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private List<DefaultSource> defaultSources() {
        return List.of(
                new DefaultSource("BAIDU", "百度新闻搜索", "BAIDU_SEARCH", "https://www.baidu.com/s", true, 10, "通过百度新闻搜索页抓取城市、交通、民生等关键词新闻。"),
                new DefaultSource("SOGOU", "搜狗新闻搜索", "SOGOU_SEARCH", "https://news.sogou.com/news", true, 20, "通过搜狗新闻搜索页补充城市和全国热点新闻。"),
                new DefaultSource("CHINANEWS_SCROLL", "中国新闻网滚动新闻", "RSS", "https://www.chinanews.com.cn/rss/scroll-news.xml", true, 30, "中国新闻网滚动新闻 RSS，适合作为全国热点和实时快讯来源。"),
                new DefaultSource("CHINANEWS_CHINA", "中国新闻网时政新闻", "RSS", "https://www.chinanews.com.cn/rss/china.xml", true, 40, "中国新闻网时政频道 RSS。"),
                new DefaultSource("CHINANEWS_SOCIETY", "中国新闻网社会新闻", "RSS", "https://www.chinanews.com.cn/rss/society.xml", true, 50, "中国新闻网社会频道 RSS。"),
                new DefaultSource("PEOPLE_SOCIETY", "人民网社会新闻", "RSS", "http://www.people.com.cn/rss/society.xml", true, 60, "人民网社会新闻 RSS，适合民生服务和全国热点兜底。")
        );
    }

    private record DefaultSource(
            String key,
            String name,
            String type,
            String endpoint,
            boolean enabled,
            int priority,
            String description
    ) {
    }
}
