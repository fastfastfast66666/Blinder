package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.support.UnauthorizedException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class AdminService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminService.class);
    private static final Pattern ADMIN_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{2,32}$");
    private static final Pattern MANAGED_USERNAME_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.-]{2,32}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;

    private final Bishe10Properties properties;
    private final SecureRandom random = new SecureRandom();

    public AdminService(Bishe10Properties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void bootstrapDefaultAdmin() {
        try (Connection connection = openConnection()) {
            if (countAdmins(connection) > 0) return;

            Bishe10Properties.Admin admin = properties.getAdmin();
            String username = normalizeAdminUsername(admin.getDefaultUsername());
            String password = admin.getDefaultPassword();
            if (isBlank(password) || password.length() < 6) {
                LOGGER.warn("admin: default admin was not created because the default password is blank or too short");
                return;
            }

            insertAdmin(connection, username, defaultAdminNickname(admin.getDefaultNickname()), password);
            LOGGER.info("admin: created default administrator username={}", username);
        } catch (SQLException | RuntimeException error) {
            LOGGER.info("admin: skipped default administrator bootstrap: {}", error.getMessage());
        }
    }

    public Map<String, Object> login(String username, String password) {
        String normalized = normalizeAdminUsername(username);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                AdminRecord admin = findAdminByUsername(connection, normalized)
                        .orElseThrow(() -> new IllegalArgumentException("管理员用户名或密码错误。"));
                if (!"enabled".equals(admin.status())) {
                    throw new IllegalStateException("管理员账号已停用。");
                }
                if (!verifyPassword(password == null ? "" : password, admin.passwordHash())) {
                    throw new IllegalArgumentException("管理员用户名或密码错误。");
                }

                markAdminLogin(connection, admin.id());
                String token = issueAdminToken(connection, admin.id());
                connection.commit();

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("token", token);
                out.put("admin", adminView(admin));
                return out;
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> me(String token) {
        AdminRecord admin = requireAdmin(token);
        return Map.of("admin", adminView(admin));
    }

    public void logout(String token) {
        if (isBlank(token)) return;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM admin_sessions WHERE token_hash = ?")) {
            statement.setString(1, sha256Hex(token.trim()));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public AdminRecord requireAdmin(String token) {
        if (isBlank(token)) {
            throw new UnauthorizedException("请先登录管理员账号。");
        }

        String tokenHash = sha256Hex(token.trim());
        Instant now = Instant.now();
        try (Connection connection = openConnection()) {
            cleanupExpiredAdminSessions(connection, now);
            String sql = """
                    SELECT a.id, a.username, a.nickname, a.password_hash, a.status, a.created_at, s.expires_at
                    FROM admin_sessions s
                    JOIN admin_users a ON a.id = s.admin_id
                    WHERE s.token_hash = ? AND s.expires_at > ?
                    LIMIT 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenHash);
                statement.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new UnauthorizedException("管理员登录已过期，请重新登录。");
                    }

                    AdminRecord admin = readAdmin(rs);
                    if (!"enabled".equals(admin.status())) {
                        throw new UnauthorizedException("管理员账号已停用。");
                    }
                    touchAdminSession(connection, tokenHash, now);
                    return admin;
                }
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> listUsers(String keyword, String status, int pageNum, int pageSize) {
        int safePageNum = Math.max(1, pageNum);
        int safePageSize = Math.min(100, Math.max(1, pageSize));
        String normalizedKeyword = keyword == null ? "" : keyword.trim();
        String normalizedStatus = normalizeOptionalStatus(status);

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> params = new ArrayList<>();
        if (!normalizedKeyword.isBlank()) {
            where.append(" AND (email LIKE ? OR nickname LIKE ? OR username LIKE ? OR phone LIKE ?)");
            String like = "%" + normalizedKeyword + "%";
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (!normalizedStatus.isBlank()) {
            where.append(" AND status = ?");
            params.add(normalizedStatus);
        }

        try (Connection connection = openConnection()) {
            long total = queryUserCount(connection, where.toString(), params);
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(safePageSize);
            pageParams.add((safePageNum - 1) * safePageSize);

            String sql = """
                    SELECT id,
                           COALESCE(NULLIF(username, ''), SUBSTRING_INDEX(email, '@', 1)) AS username,
                           email,
                           nickname,
                           COALESCE(phone, '') AS phone,
                           COALESCE(status, 'enabled') AS status,
                           created_at,
                           last_login_at
                    FROM users
                    """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?";

            List<Map<String, Object>> records = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindParams(statement, pageParams);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        records.add(readManagedUser(rs));
                    }
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("records", records);
            out.put("total", total);
            out.put("pageNum", safePageNum);
            out.put("pageSize", safePageSize);
            return out;
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> createUser(Map<String, Object> body) {
        String username = normalizeManagedUsername(asString(body.get("username")));
        String email = normalizeEmail(asString(body.get("email")));
        String nickname = normalizeNickname(asString(body.get("nickname")), email);
        String phone = normalizePhone(asString(body.get("phone")));
        String status = normalizeRequiredStatus(asString(body.get("status")));
        String password = asString(body.get("password"));
        validatePassword(password);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureUsernameAvailable(connection, username, 0);
                ensureEmailAvailable(connection, email, 0);

                String sql = """
                        INSERT INTO users (username, email, nickname, phone, status, password_hash, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                Instant now = Instant.now();
                long userId;
                try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, username);
                    statement.setString(2, email);
                    statement.setString(3, nickname);
                    statement.setString(4, phone);
                    statement.setString(5, status);
                    statement.setString(6, hashPassword(password));
                    statement.setTimestamp(7, Timestamp.from(now));
                    statement.setTimestamp(8, Timestamp.from(now));
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new IllegalStateException("用户创建失败，请稍后再试。");
                        }
                        userId = keys.getLong(1);
                    }
                }

                Map<String, Object> user = findManagedUserById(connection, userId)
                        .orElseThrow(() -> new IllegalStateException("用户创建失败，请稍后再试。"));
                connection.commit();
                return user;
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> updateUser(long id, Map<String, Object> body) {
        if (id <= 0) {
            throw new IllegalArgumentException("用户 ID 不正确。");
        }

        String username = normalizeManagedUsername(asString(body.get("username")));
        String email = normalizeEmail(asString(body.get("email")));
        String nickname = normalizeNickname(asString(body.get("nickname")), email);
        String phone = normalizePhone(asString(body.get("phone")));
        String status = normalizeRequiredStatus(asString(body.get("status")));
        String password = asString(body.get("password"));
        if (!password.isBlank()) validatePassword(password);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (findManagedUserById(connection, id).isEmpty()) {
                    throw new IllegalArgumentException("用户不存在。");
                }
                ensureUsernameAvailable(connection, username, id);
                ensureEmailAvailable(connection, email, id);

                Timestamp now = Timestamp.from(Instant.now());
                if (password.isBlank()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE users
                            SET username = ?, email = ?, nickname = ?, phone = ?, status = ?, updated_at = ?
                            WHERE id = ?
                            """)) {
                        statement.setString(1, username);
                        statement.setString(2, email);
                        statement.setString(3, nickname);
                        statement.setString(4, phone);
                        statement.setString(5, status);
                        statement.setTimestamp(6, now);
                        statement.setLong(7, id);
                        statement.executeUpdate();
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE users
                            SET username = ?, email = ?, nickname = ?, phone = ?, status = ?, password_hash = ?, updated_at = ?
                            WHERE id = ?
                            """)) {
                        statement.setString(1, username);
                        statement.setString(2, email);
                        statement.setString(3, nickname);
                        statement.setString(4, phone);
                        statement.setString(5, status);
                        statement.setString(6, hashPassword(password));
                        statement.setTimestamp(7, now);
                        statement.setLong(8, id);
                        statement.executeUpdate();
                    }
                }

                Map<String, Object> user = findManagedUserById(connection, id)
                        .orElseThrow(() -> new IllegalArgumentException("用户不存在。"));
                connection.commit();
                return user;
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public void deleteUser(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("用户 ID 不正确。");
        }

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
            statement.setLong(1, id);
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("用户不存在。");
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private Connection openConnection() throws SQLException {
        Bishe10Properties.Auth.Database db = properties.getAuth().getDb();
        if (db.getUrl() == null || db.getUrl().isBlank()) {
            throw new IllegalStateException("请先配置 MySQL 连接地址。");
        }
        return DriverManager.getConnection(db.getUrl(), db.getUsername(), db.getPassword());
    }

    private long countAdmins(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM admin_users");
             ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void insertAdmin(Connection connection, String username, String nickname, String password) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO admin_users (username, nickname, password_hash, status, created_at, updated_at)
                VALUES (?, ?, ?, 'enabled', ?, ?)
                """)) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setString(1, username);
            statement.setString(2, nickname);
            statement.setString(3, hashPassword(password));
            statement.setTimestamp(4, now);
            statement.setTimestamp(5, now);
            statement.executeUpdate();
        }
    }

    private Optional<AdminRecord> findAdminByUsername(Connection connection, String username) throws SQLException {
        String sql = """
                SELECT id, username, nickname, password_hash, status, created_at
                FROM admin_users
                WHERE username = ?
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readAdmin(rs));
            }
        }
    }

    private String issueAdminToken(Connection connection, long adminId) throws SQLException {
        String token = randomToken();
        Instant now = Instant.now();
        int ttlHours = Math.max(1, properties.getAdmin().getTokenTtlHours());
        Instant expiresAt = now.plus(Duration.ofHours(ttlHours));
        String sql = """
                INSERT INTO admin_sessions (token_hash, admin_id, expires_at, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sha256Hex(token));
            statement.setLong(2, adminId);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
        }
        return token;
    }

    private void markAdminLogin(Connection connection, long adminId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE admin_users SET last_login_at = ?, updated_at = ? WHERE id = ?")) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.setLong(3, adminId);
            statement.executeUpdate();
        }
    }

    private void touchAdminSession(Connection connection, String tokenHash, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE admin_sessions SET last_seen_at = ? WHERE token_hash = ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, tokenHash);
            statement.executeUpdate();
        }
    }

    private void cleanupExpiredAdminSessions(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM admin_sessions WHERE expires_at < ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private long queryUserCount(Connection connection, String where, List<Object> params) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM users" + where)) {
            bindParams(statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private Optional<Map<String, Object>> findManagedUserById(Connection connection, long id) throws SQLException {
        String sql = """
                SELECT id,
                       COALESCE(NULLIF(username, ''), SUBSTRING_INDEX(email, '@', 1)) AS username,
                       email,
                       nickname,
                       COALESCE(phone, '') AS phone,
                       COALESCE(status, 'enabled') AS status,
                       created_at,
                       last_login_at
                FROM users
                WHERE id = ?
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readManagedUser(rs));
            }
        }
    }

    private void ensureUsernameAvailable(Connection connection, String username, long ignoreId) throws SQLException {
        String sql = ignoreId > 0
                ? "SELECT id FROM users WHERE username = ? AND id <> ? LIMIT 1"
                : "SELECT id FROM users WHERE username = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            if (ignoreId > 0) statement.setLong(2, ignoreId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("用户名已存在。");
                }
            }
        }
    }

    private void ensureEmailAvailable(Connection connection, String email, long ignoreId) throws SQLException {
        String sql = ignoreId > 0
                ? "SELECT id FROM users WHERE email = ? AND id <> ? LIMIT 1"
                : "SELECT id FROM users WHERE email = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            if (ignoreId > 0) statement.setLong(2, ignoreId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    throw new IllegalArgumentException("邮箱已存在。");
                }
            }
        }
    }

    private void bindParams(PreparedStatement statement, List<Object> params) throws SQLException {
        for (int index = 0; index < params.size(); index++) {
            Object value = params.get(index);
            int parameterIndex = index + 1;
            if (value instanceof Integer intValue) {
                statement.setInt(parameterIndex, intValue);
            } else if (value instanceof Long longValue) {
                statement.setLong(parameterIndex, longValue);
            } else {
                statement.setString(parameterIndex, String.valueOf(value));
            }
        }
    }

    private AdminRecord readAdmin(ResultSet rs) throws SQLException {
        return new AdminRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("nickname"),
                rs.getString("password_hash"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private Map<String, Object> readManagedUser(ResultSet rs) throws SQLException {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(rs.getLong("id")));
        view.put("username", rs.getString("username"));
        view.put("email", rs.getString("email"));
        view.put("nickname", rs.getString("nickname"));
        view.put("phone", rs.getString("phone"));
        view.put("status", rs.getString("status"));
        view.put("createdAt", timestampText(rs, "created_at"));
        view.put("lastLoginAt", timestampText(rs, "last_login_at"));
        return view;
    }

    private Map<String, Object> adminView(AdminRecord admin) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(admin.id()));
        view.put("username", admin.username());
        view.put("nickname", admin.nickname());
        view.put("status", admin.status());
        view.put("createdAt", admin.createdAt().toString());
        return view;
    }

    private String timestampText(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        if (timestamp == null) return "";
        return timestamp.toLocalDateTime().format(DATE_TIME_FORMATTER);
    }

    private String normalizeAdminUsername(String username) {
        if (isBlank(username)) {
            throw new IllegalArgumentException("请输入管理员用户名。");
        }
        String normalized = username.trim();
        if (!ADMIN_USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("管理员用户名只能包含字母、数字、下划线、点和横线，长度为 2-32 位。");
        }
        return normalized;
    }

    private String normalizeManagedUsername(String username) {
        if (isBlank(username)) {
            throw new IllegalArgumentException("请输入用户名。");
        }
        String normalized = username.trim();
        if (!MANAGED_USERNAME_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("用户名只能包含中文、字母、数字、下划线、点和横线，长度为 2-32 位。");
        }
        return normalized;
    }

    private String normalizeEmail(String email) {
        if (isBlank(email)) {
            throw new IllegalArgumentException("请输入有效的邮箱地址。");
        }
        String normalized = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("请输入有效的邮箱地址。");
        }
        return normalized;
    }

    private String normalizeNickname(String nickname, String email) {
        if (isBlank(nickname)) return defaultNickname(email);
        String normalized = nickname.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("昵称不能超过 100 位。");
        }
        return normalized;
    }

    private String normalizePhone(String phone) {
        if (phone == null) return "";
        String normalized = phone.trim();
        if (normalized.length() > 32) {
            throw new IllegalArgumentException("手机号不能超过 32 位。");
        }
        return normalized;
    }

    private String normalizeOptionalStatus(String status) {
        if (isBlank(status)) return "";
        return normalizeRequiredStatus(status);
    }

    private String normalizeRequiredStatus(String status) {
        if (isBlank(status)) return "enabled";
        String normalized = status.trim();
        if (!"enabled".equals(normalized) && !"disabled".equals(normalized)) {
            throw new IllegalArgumentException("用户状态不正确。");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位。");
        }
    }

    private String defaultAdminNickname(String nickname) {
        return isBlank(nickname) ? "系统管理员" : nickname.trim();
    }

    private String defaultNickname(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomBase64(int bytesLength) {
        byte[] bytes = new byte[bytesLength];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private String hashPassword(String password) {
        String salt = randomBase64(16);
        byte[] hash = pbkdf2(password.toCharArray(), Base64.getDecoder().decode(salt), PASSWORD_ITERATIONS);
        return "pbkdf2_sha256$" + PASSWORD_ITERATIONS + "$" + salt + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private boolean verifyPassword(String password, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) return false;
        String[] parts = storedHash.split("\\$");
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception error) {
            throw new IllegalStateException("PBKDF2 unavailable", error);
        } finally {
            spec.clearPassword();
        }
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

    private RuntimeException databaseError(SQLException error) {
        LOGGER.warn(
                "admin: database operation failed sqlState={} vendorCode={} message={}",
                error.getSQLState(),
                error.getErrorCode(),
                error.getMessage(),
                error
        );
        String message = error.getMessage() == null ? "" : error.getMessage();
        if ("42S02".equals(error.getSQLState())
                || message.contains("admin_users")
                || message.contains("admin_sessions")
                || message.contains("Unknown column 'username'")
                || message.contains("Unknown column 'phone'")
                || message.contains("Unknown column 'status'")) {
            return new IllegalStateException("管理员数据库表还没有初始化，请先执行 backend/sql/bishe10_auth_mysql.sql。");
        }
        return new IllegalStateException("管理员数据库操作失败，请检查 MySQL 配置。");
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            LOGGER.warn("admin: failed to rollback transaction", rollbackError);
        }
    }

    public record AdminRecord(long id, String username, String nickname, String passwordHash, String status, Instant createdAt) {
    }
}
