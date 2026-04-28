package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class AdminDashboardRepository extends JdbcRepositorySupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminDashboardRepository.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public AdminDashboardRepository(Bishe10Properties properties) {
        super(properties);
    }

    public Map<String, Object> loadDatabaseSummary() {
        Map<String, Object> payload = new LinkedHashMap<>();
        try (Connection connection = openConnection()) {
            payload.put("available", true);
            payload.put("message", "");
            payload.put("users", loadUsers(connection));
            payload.put("admins", loadAdmins(connection));
            payload.put("news", loadNews(connection));
            payload.put("feedback", loadFeedback(connection));
            payload.put("sources", loadSources(connection));
            payload.put("personalization", loadPersonalization(connection));
        } catch (Exception error) {
            LOGGER.warn("admin dashboard: database summary unavailable", error);
            payload.put("available", false);
            payload.put("message", "数据库暂不可用或尚未初始化");
            payload.put("users", emptyUsers());
            payload.put("admins", emptyAdmins());
            payload.put("news", emptyNews());
            payload.put("feedback", emptyFeedback());
            payload.put("sources", emptySources());
            payload.put("personalization", emptyPersonalization());
        }
        return payload;
    }

    private Map<String, Object> loadUsers(Connection connection) {
        Map<String, Object> users = new LinkedHashMap<>();
        long total = safeCount(connection, "SELECT COUNT(*) FROM users");
        users.put("total", total);
        users.put("enabled", safeCount(connection, "SELECT COUNT(*) FROM users WHERE COALESCE(status, 'enabled') = 'enabled'"));
        users.put("disabled", safeCount(connection, "SELECT COUNT(*) FROM users WHERE COALESCE(status, 'enabled') = 'disabled'"));
        users.put("newToday", safeCount(connection, "SELECT COUNT(*) FROM users WHERE created_at >= CURRENT_DATE()"));
        users.put("activeLast7Days", safeCount(connection, "SELECT COUNT(*) FROM users WHERE last_login_at >= DATE_SUB(NOW(3), INTERVAL 7 DAY)"));
        return users;
    }

    private Map<String, Object> loadAdmins(Connection connection) {
        Map<String, Object> admins = new LinkedHashMap<>();
        admins.put("total", safeCount(connection, "SELECT COUNT(*) FROM admin_users"));
        admins.put("enabled", safeCount(connection, "SELECT COUNT(*) FROM admin_users WHERE status = 'enabled'"));
        admins.put("activeSessions", safeCount(connection, "SELECT COUNT(*) FROM admin_sessions WHERE expires_at > NOW(3)"));
        return admins;
    }

    private Map<String, Object> loadNews(Connection connection) {
        Map<String, Object> news = new LinkedHashMap<>();
        news.put("total", safeCount(connection, "SELECT COUNT(*) FROM news_article"));
        news.put("realArticles", safeCount(connection, """
                SELECT COUNT(*)
                FROM news_article
                WHERE COALESCE(source, '') NOT IN ('SYSTEM_TEMPLATE', 'AI_GENERATED')
                  AND COALESCE(fetch_scope, '') <> 'FALLBACK'
                """));
        news.put("syntheticArticles", safeCount(connection, """
                SELECT COUNT(*)
                FROM news_article
                WHERE COALESCE(source, '') IN ('SYSTEM_TEMPLATE', 'AI_GENERATED')
                   OR COALESCE(fetch_scope, '') = 'FALLBACK'
                """));
        news.put("updatedToday", safeCount(connection, "SELECT COUNT(*) FROM news_article WHERE updated_at >= CURRENT_DATE()"));
        news.put("latestUpdatedAt", safeTimestamp(connection, "SELECT MAX(COALESCE(publish_time, updated_at)) FROM news_article"));
        news.put("scopeCounts", orderedRows(
                safeGroupCounts(connection, """
                        SELECT COALESCE(NULLIF(fetch_scope, ''), 'UNKNOWN') AS item_key, COUNT(*) AS item_count
                        FROM news_article
                        GROUP BY COALESCE(NULLIF(fetch_scope, ''), 'UNKNOWN')
                        """),
                List.of(
                        new MetricLabel("CITY", "城市新闻"),
                        new MetricLabel("INTEREST", "兴趣扩展"),
                        new MetricLabel("PROVINCE", "同省补充"),
                        new MetricLabel("NATIONAL", "全国热点"),
                        new MetricLabel("FALLBACK", "兜底内容"),
                        new MetricLabel("UNKNOWN", "未标记")
                )
        ));
        news.put("recentArticles", loadRecentArticles(connection));
        return news;
    }

    private Map<String, Object> loadFeedback(Connection connection) {
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("total", safeCount(connection, "SELECT COUNT(*) FROM user_news_feedback"));
        feedback.put("today", safeCount(connection, "SELECT COUNT(*) FROM user_news_feedback WHERE created_at >= CURRENT_DATE()"));
        feedback.put("users", safeCount(connection, "SELECT COUNT(DISTINCT user_id) FROM user_news_feedback"));
        feedback.put("articles", safeCount(connection, "SELECT COUNT(DISTINCT article_id) FROM user_news_feedback"));
        feedback.put("actionCounts", orderedRows(
                safeGroupCounts(connection, """
                        SELECT COALESCE(NULLIF(action, ''), 'UNKNOWN') AS item_key, COUNT(*) AS item_count
                        FROM user_news_feedback
                        GROUP BY COALESCE(NULLIF(action, ''), 'UNKNOWN')
                        """),
                List.of(
                        new MetricLabel("VIEW", "浏览"),
                        new MetricLabel("LIKE", "喜欢"),
                        new MetricLabel("FAVORITE", "收藏"),
                        new MetricLabel("DISLIKE", "不喜欢"),
                        new MetricLabel("SKIP", "跳过"),
                        new MetricLabel("NOT_INTERESTED", "不感兴趣"),
                        new MetricLabel("BLOCK_SIMILAR", "屏蔽相似"),
                        new MetricLabel("UNKNOWN", "未标记")
                )
        ));
        return feedback;
    }

    private Map<String, Object> loadSources(Connection connection) {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("total", safeCount(connection, "SELECT COUNT(*) FROM news_source_config"));
        sources.put("enabled", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE enabled = 1"));
        sources.put("disabled", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE enabled = 0"));
        sources.put("rss", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE source_type = 'RSS'"));
        sources.put("search", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE source_type <> 'RSS'"));
        sources.put("healthy", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE last_status = 'OK'"));
        sources.put("warning", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE last_status IN ('EMPTY', 'BLOCKED', 'COOLDOWN')"));
        sources.put("error", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE last_status = 'ERROR'"));
        sources.put("pending", safeCount(connection, "SELECT COUNT(*) FROM news_source_config WHERE COALESCE(NULLIF(last_status, ''), 'INIT') = 'INIT'"));
        sources.put("items", loadSourceItems(connection));
        return sources;
    }

    private Map<String, Object> loadPersonalization(Connection connection) {
        Map<String, Object> personalization = new LinkedHashMap<>();
        personalization.put("interestProfiles", safeCount(connection, "SELECT COUNT(*) FROM user_interest_profile"));
        personalization.put("profileUsers", safeCount(connection, "SELECT COUNT(DISTINCT user_id) FROM user_interest_profile"));
        personalization.put("blockRules", safeCount(connection, "SELECT COUNT(*) FROM user_block_rule"));
        return personalization;
    }

    private List<Map<String, Object>> loadRecentArticles(Connection connection) {
        String sql = """
                SELECT article_id, title, source, city, province, category, fetch_scope, publish_time, updated_at
                FROM news_article
                ORDER BY COALESCE(publish_time, updated_at) DESC, updated_at DESC
                LIMIT 8
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("articleId", text(rs, "article_id"));
                row.put("title", text(rs, "title"));
                row.put("source", text(rs, "source"));
                row.put("city", text(rs, "city"));
                row.put("province", text(rs, "province"));
                row.put("category", text(rs, "category"));
                row.put("fetchScope", text(rs, "fetch_scope"));
                row.put("publishTime", timestampText(rs, "publish_time"));
                row.put("updatedAt", timestampText(rs, "updated_at"));
                rows.add(row);
            }
            return rows;
        } catch (SQLException error) {
            LOGGER.debug("admin dashboard: recent article query failed", error);
            return List.of();
        }
    }

    private List<Map<String, Object>> loadSourceItems(Connection connection) {
        String sql = """
                SELECT source_key, source_name, source_type, enabled, priority, last_status, last_fetch_at, updated_at
                FROM news_source_config
                ORDER BY priority ASC, source_key ASC
                LIMIT 12
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sourceKey", text(rs, "source_key"));
                row.put("sourceName", text(rs, "source_name"));
                row.put("sourceType", text(rs, "source_type"));
                row.put("enabled", rs.getInt("enabled") == 1);
                row.put("priority", rs.getInt("priority"));
                row.put("lastStatus", text(rs, "last_status"));
                row.put("lastFetchAt", timestampText(rs, "last_fetch_at"));
                row.put("updatedAt", timestampText(rs, "updated_at"));
                rows.add(row);
            }
            return rows;
        } catch (SQLException error) {
            LOGGER.debug("admin dashboard: source item query failed", error);
            return List.of();
        }
    }

    private long safeCount(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException error) {
            LOGGER.debug("admin dashboard: count query failed sql={}", sql, error);
            return 0L;
        }
    }

    private Map<String, Long> safeGroupCounts(Connection connection, String sql) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                String key = rs.getString(1);
                counts.put(key == null || key.isBlank() ? "UNKNOWN" : key.trim().toUpperCase(), rs.getLong(2));
            }
        } catch (SQLException error) {
            LOGGER.debug("admin dashboard: group query failed sql={}", sql, error);
        }
        return counts;
    }

    private String safeTimestamp(Connection connection, String sql) {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) return "";
            Timestamp timestamp = rs.getTimestamp(1);
            return timestamp == null ? "" : timestamp.toLocalDateTime().format(DATE_TIME_FORMATTER);
        } catch (SQLException error) {
            LOGGER.debug("admin dashboard: timestamp query failed sql={}", sql, error);
            return "";
        }
    }

    private List<Map<String, Object>> orderedRows(Map<String, Long> counts, List<MetricLabel> labels) {
        return labels.stream()
                .map(label -> metricRow(label.key(), label.label(), counts.getOrDefault(label.key(), 0L)))
                .toList();
    }

    private Map<String, Object> metricRow(String key, String label, long value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("label", label);
        row.put("value", value);
        return row;
    }

    private String timestampText(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? "" : timestamp.toLocalDateTime().format(DATE_TIME_FORMATTER);
    }

    private String text(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? "" : value;
    }

    private Map<String, Object> emptyUsers() {
        Map<String, Object> users = new LinkedHashMap<>();
        users.put("total", 0L);
        users.put("enabled", 0L);
        users.put("disabled", 0L);
        users.put("newToday", 0L);
        users.put("activeLast7Days", 0L);
        return users;
    }

    private Map<String, Object> emptyAdmins() {
        Map<String, Object> admins = new LinkedHashMap<>();
        admins.put("total", 0L);
        admins.put("enabled", 0L);
        admins.put("activeSessions", 0L);
        return admins;
    }

    private Map<String, Object> emptyNews() {
        Map<String, Object> news = new LinkedHashMap<>();
        news.put("total", 0L);
        news.put("realArticles", 0L);
        news.put("syntheticArticles", 0L);
        news.put("updatedToday", 0L);
        news.put("latestUpdatedAt", "");
        news.put("scopeCounts", orderedRows(Map.of(), List.of(
                new MetricLabel("CITY", "城市新闻"),
                new MetricLabel("INTEREST", "兴趣扩展"),
                new MetricLabel("PROVINCE", "同省补充"),
                new MetricLabel("NATIONAL", "全国热点"),
                new MetricLabel("FALLBACK", "兜底内容"),
                new MetricLabel("UNKNOWN", "未标记")
        )));
        news.put("recentArticles", List.of());
        return news;
    }

    private Map<String, Object> emptyFeedback() {
        Map<String, Object> feedback = new LinkedHashMap<>();
        feedback.put("total", 0L);
        feedback.put("today", 0L);
        feedback.put("users", 0L);
        feedback.put("articles", 0L);
        feedback.put("actionCounts", orderedRows(Map.of(), List.of(
                new MetricLabel("VIEW", "浏览"),
                new MetricLabel("LIKE", "喜欢"),
                new MetricLabel("FAVORITE", "收藏"),
                new MetricLabel("DISLIKE", "不喜欢"),
                new MetricLabel("SKIP", "跳过"),
                new MetricLabel("NOT_INTERESTED", "不感兴趣"),
                new MetricLabel("BLOCK_SIMILAR", "屏蔽相似"),
                new MetricLabel("UNKNOWN", "未标记")
        )));
        return feedback;
    }

    private Map<String, Object> emptySources() {
        Map<String, Object> sources = new LinkedHashMap<>();
        sources.put("total", 0L);
        sources.put("enabled", 0L);
        sources.put("disabled", 0L);
        sources.put("rss", 0L);
        sources.put("search", 0L);
        sources.put("healthy", 0L);
        sources.put("warning", 0L);
        sources.put("error", 0L);
        sources.put("pending", 0L);
        sources.put("items", List.of());
        return sources;
    }

    private Map<String, Object> emptyPersonalization() {
        Map<String, Object> personalization = new LinkedHashMap<>();
        personalization.put("interestProfiles", 0L);
        personalization.put("profileUsers", 0L);
        personalization.put("blockRules", 0L);
        return personalization;
    }

    private record MetricLabel(String key, String label) {
    }
}
