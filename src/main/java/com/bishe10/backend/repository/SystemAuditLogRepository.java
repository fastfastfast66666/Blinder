package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SystemAuditLogRepository extends JdbcRepositorySupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemAuditLogRepository.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public SystemAuditLogRepository(Bishe10Properties properties) {
        super(properties);
    }

    public AuditActor resolveActor(String token) {
        if (token == null || token.isBlank()) {
            return AuditActor.guest();
        }
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            String tokenHash = sha256Hex(token.trim());
            AuditActor admin = resolveAdmin(connection, tokenHash);
            if (admin != null) return admin;
            AuditActor user = resolveUser(connection, tokenHash);
            if (user != null) return user;
        } catch (Exception error) {
            LOGGER.debug("system audit: resolve actor failed", error);
        }
        return AuditActor.guest();
    }

    public void save(AuditEvent event) {
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            String sql = """
                    INSERT INTO system_audit_log (
                      actor_type, actor_id, actor_name, module_key, module_name,
                      action_key, action_name, target_type, target_id, http_method,
                      request_path, query_string, status_code, success, duration_ms,
                      ip_address, user_agent, message, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3))
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, event.actorType());
                statement.setString(2, event.actorId());
                statement.setString(3, event.actorName());
                statement.setString(4, event.moduleKey());
                statement.setString(5, event.moduleName());
                statement.setString(6, event.actionKey());
                statement.setString(7, event.actionName());
                statement.setString(8, event.targetType());
                statement.setString(9, event.targetId());
                statement.setString(10, event.httpMethod());
                statement.setString(11, event.requestPath());
                statement.setString(12, event.queryString());
                statement.setInt(13, event.statusCode());
                statement.setInt(14, event.success() ? 1 : 0);
                statement.setLong(15, event.durationMs());
                statement.setString(16, event.ipAddress());
                statement.setString(17, event.userAgent());
                statement.setString(18, event.message());
                statement.executeUpdate();
            }
        } catch (Exception error) {
            LOGGER.debug("system audit: save log failed path={}", event.requestPath(), error);
        }
    }

    public Map<String, Object> listLogs(Query query) {
        try (Connection connection = openConnection()) {
            ensureTable(connection);
            SqlWhere where = buildWhere(query);
            long total = count(connection, "SELECT COUNT(*) FROM system_audit_log" + where.sql(), where.params());

            int pageNum = Math.max(1, query.pageNum());
            int pageSize = Math.min(100, Math.max(1, query.pageSize()));
            List<Object> pageParams = new ArrayList<>(where.params());
            pageParams.add(pageSize);
            pageParams.add((pageNum - 1) * pageSize);

            String sql = """
                    SELECT id, actor_type, actor_id, actor_name, module_key, module_name,
                           action_key, action_name, target_type, target_id, http_method,
                           request_path, query_string, status_code, success, duration_ms,
                           ip_address, user_agent, message, created_at
                    FROM system_audit_log
                    """ + where.sql() + " ORDER BY id DESC LIMIT ? OFFSET ?";

            List<Map<String, Object>> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, pageParams);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        records.add(readLog(rs));
                    }
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("records", records);
            out.put("total", total);
            out.put("pageNum", pageNum);
            out.put("pageSize", pageSize);
            out.put("summary", summary(connection));
            out.put("modules", modules());
            return out;
        } catch (SQLException error) {
            LOGGER.warn("system audit: list logs failed", error);
            throw new IllegalStateException("系统日志读取失败，请确认数据库连接正常。");
        }
    }

    private AuditActor resolveAdmin(Connection connection, String tokenHash) throws SQLException {
        String sql = """
                SELECT a.id, a.username, a.nickname
                FROM admin_sessions s
                JOIN admin_users a ON a.id = s.admin_id
                WHERE s.token_hash = ? AND s.expires_at > NOW(3)
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                String name = firstNonBlank(rs.getString("nickname"), rs.getString("username"), "管理员");
                return new AuditActor("ADMIN", String.valueOf(rs.getLong("id")), name);
            }
        } catch (SQLException error) {
            LOGGER.debug("system audit: resolve admin failed", error);
            return null;
        }
    }

    private AuditActor resolveUser(Connection connection, String tokenHash) throws SQLException {
        String sql = """
                SELECT u.id, u.email, u.nickname
                FROM auth_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > NOW(3)
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return null;
                String name = firstNonBlank(rs.getString("nickname"), rs.getString("email"), "小程序用户");
                return new AuditActor("USER", String.valueOf(rs.getLong("id")), name);
            }
        } catch (SQLException error) {
            LOGGER.debug("system audit: resolve user failed", error);
            return null;
        }
    }

    private SqlWhere buildWhere(Query query) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();

        if (!isBlank(query.keyword())) {
            where.append("""
                     AND (
                       actor_name LIKE ? OR actor_id LIKE ? OR module_name LIKE ? OR action_name LIKE ?
                       OR request_path LIKE ? OR ip_address LIKE ? OR message LIKE ?
                     )
                    """);
            String like = "%" + query.keyword().trim() + "%";
            for (int i = 0; i < 7; i++) params.add(like);
        }
        if (!isBlank(query.moduleKey())) {
            where.append(" AND module_key = ?");
            params.add(query.moduleKey().trim());
        }
        if (!isBlank(query.actorType())) {
            where.append(" AND actor_type = ?");
            params.add(query.actorType().trim().toUpperCase());
        }
        if ("success".equalsIgnoreCase(query.result())) {
            where.append(" AND success = 1");
        } else if ("failed".equalsIgnoreCase(query.result())) {
            where.append(" AND success = 0");
        }
        if (!isBlank(query.startTime())) {
            where.append(" AND created_at >= ?");
            params.add(query.startTime().trim());
        }
        if (!isBlank(query.endTime())) {
            where.append(" AND created_at <= ?");
            params.add(query.endTime().trim());
        }
        return new SqlWhere(where.toString(), params);
    }

    private Map<String, Object> summary(Connection connection) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", count(connection, "SELECT COUNT(*) FROM system_audit_log", List.of()));
        summary.put("today", count(connection, "SELECT COUNT(*) FROM system_audit_log WHERE created_at >= CURRENT_DATE()", List.of()));
        summary.put("success", count(connection, "SELECT COUNT(*) FROM system_audit_log WHERE success = 1", List.of()));
        summary.put("failed", count(connection, "SELECT COUNT(*) FROM system_audit_log WHERE success = 0", List.of()));
        summary.put("authEvents", count(connection, "SELECT COUNT(*) FROM system_audit_log WHERE module_key IN ('auth', 'admin_auth')", List.of()));
        summary.put("adminEvents", count(connection, "SELECT COUNT(*) FROM system_audit_log WHERE request_path LIKE '/api/admin/%'", List.of()));
        return summary;
    }

    private List<Map<String, String>> modules() {
        return List.of(
                module("admin_auth", "管理员登录"),
                module("auth", "用户登录注册"),
                module("admin_dashboard", "数据总览"),
                module("admin_user", "用户管理"),
                module("admin_news_source", "新闻源管理"),
                module("admin_algorithm", "新闻算法管理"),
                module("admin_audit", "系统日志"),
                module("news", "新闻资讯"),
                module("news_feedback", "新闻反馈"),
                module("weather", "天气服务"),
                module("vision", "识图服务"),
                module("voice", "语音服务"),
                module("history", "历史记录"),
                module("personalization", "个性化画像"),
                module("system", "系统接口")
        );
    }

    private Map<String, String> module(String value, String label) {
        Map<String, String> module = new LinkedHashMap<>();
        module.put("value", value);
        module.put("label", label);
        return module;
    }

    private long count(Connection connection, String sql, List<Object> params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException error) {
            LOGGER.debug("system audit: count failed sql={}", sql, error);
            return 0L;
        }
    }

    private Map<String, Object> readLog(ResultSet rs) throws SQLException {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("id", String.valueOf(rs.getLong("id")));
        log.put("actorType", rs.getString("actor_type"));
        log.put("actorId", rs.getString("actor_id"));
        log.put("actorName", rs.getString("actor_name"));
        log.put("moduleKey", rs.getString("module_key"));
        log.put("moduleName", rs.getString("module_name"));
        log.put("actionKey", rs.getString("action_key"));
        log.put("actionName", rs.getString("action_name"));
        log.put("targetType", rs.getString("target_type"));
        log.put("targetId", rs.getString("target_id"));
        log.put("httpMethod", rs.getString("http_method"));
        log.put("requestPath", rs.getString("request_path"));
        log.put("queryString", rs.getString("query_string"));
        log.put("statusCode", rs.getInt("status_code"));
        log.put("success", rs.getInt("success") == 1);
        log.put("durationMs", rs.getLong("duration_ms"));
        log.put("ipAddress", rs.getString("ip_address"));
        log.put("userAgent", rs.getString("user_agent"));
        log.put("message", rs.getString("message"));
        log.put("createdAt", timestampText(rs.getTimestamp("created_at")));
        return log;
    }

    private void ensureTable(Connection connection) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS system_audit_log (
                  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                  actor_type VARCHAR(32) NOT NULL DEFAULT 'GUEST',
                  actor_id VARCHAR(64) NULL,
                  actor_name VARCHAR(128) NULL,
                  module_key VARCHAR(64) NOT NULL,
                  module_name VARCHAR(128) NOT NULL,
                  action_key VARCHAR(64) NOT NULL,
                  action_name VARCHAR(128) NOT NULL,
                  target_type VARCHAR(64) NULL,
                  target_id VARCHAR(128) NULL,
                  http_method VARCHAR(16) NOT NULL,
                  request_path VARCHAR(512) NOT NULL,
                  query_string VARCHAR(1024) NULL,
                  status_code INT NOT NULL,
                  success TINYINT(1) NOT NULL DEFAULT 1,
                  duration_ms BIGINT NOT NULL DEFAULT 0,
                  ip_address VARCHAR(64) NULL,
                  user_agent VARCHAR(512) NULL,
                  message VARCHAR(512) NULL,
                  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                  PRIMARY KEY (id),
                  KEY idx_audit_created_at (created_at),
                  KEY idx_audit_module_created (module_key, created_at),
                  KEY idx_audit_actor_created (actor_type, actor_id, created_at),
                  KEY idx_audit_success_created (success, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """;
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            if (value instanceof Integer intValue) {
                statement.setInt(i + 1, intValue);
            } else if (value instanceof Long longValue) {
                statement.setLong(i + 1, longValue);
            } else {
                statement.setString(i + 1, value == null ? "" : String.valueOf(value));
            }
        }
    }

    private String timestampText(Timestamp timestamp) {
        return timestamp == null ? "" : timestamp.toLocalDateTime().format(DATE_TIME_FORMATTER);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public record AuditActor(String actorType, String actorId, String actorName) {
        static AuditActor guest() {
            return new AuditActor("GUEST", "", "访客");
        }
    }

    public record AuditEvent(
            String actorType,
            String actorId,
            String actorName,
            String moduleKey,
            String moduleName,
            String actionKey,
            String actionName,
            String targetType,
            String targetId,
            String httpMethod,
            String requestPath,
            String queryString,
            int statusCode,
            boolean success,
            long durationMs,
            String ipAddress,
            String userAgent,
            String message
    ) {
    }

    public record Query(
            String keyword,
            String moduleKey,
            String actorType,
            String result,
            String startTime,
            String endTime,
            int pageNum,
            int pageSize
    ) {
    }

    private record SqlWhere(String sql, List<Object> params) {
    }
}
