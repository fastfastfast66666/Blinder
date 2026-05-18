package com.bishe10.backend.service;

import com.bishe10.backend.config.Bishe10Properties;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Real account registration/login backed by MySQL.
 *
 * Required tables are created by sql/bishe10_auth_mysql.sql. Verification codes
 * and sessions are also stored in MySQL, so restarts do not silently log users
 * out or lose pending email codes.
 */
@Service
public class AuthService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final int PASSWORD_KEY_BITS = 256;

    private final Bishe10Properties properties;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean dbConfigLogged = new AtomicBoolean(false);

    public AuthService(Bishe10Properties properties) {
        this.properties = properties;
    }

    public Map<String, Object> sendCode(String email) {
        String normalized = normalizeEmail(email);
        Instant now = Instant.now();
        int cooldownSeconds = Math.max(1, properties.getAuth().getCodeResendCooldownSeconds());
        int ttlSeconds = Math.max(60, properties.getAuth().getCodeTtlSeconds());

        try (Connection connection = openConnection()) {
            cleanupExpiredCodes(connection, now);
            enforceCooldown(connection, normalized, now, cooldownSeconds);

            String code = generateCode();
            String codeHash = hashVerificationCode(normalized, code);
            long codeId = insertVerificationCode(connection, normalized, codeHash, now, ttlSeconds);
            try {
                sendVerificationEmail(normalized, code, ttlSeconds);
            } catch (RuntimeException error) {
                deleteVerificationCode(connection, codeId);
                throw error;
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("sent", true);
            out.put("ttlSec", ttlSeconds);
            out.put("cooldownSec", cooldownSeconds);
            out.put("email", normalized);
            return out;
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> register(String email, String code, String password, String nickname) {
        String normalized = normalizeEmail(email);
        validatePassword(password);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (findUserByEmail(connection, normalized).isPresent()) {
                    throw new IllegalStateException("\u8be5\u90ae\u7bb1\u5df2\u6ce8\u518c\uff0c\u8bf7\u76f4\u63a5\u767b\u5f55\u3002");
                }

                consumeVerificationCode(connection, normalized, code);

                UserRecord user = insertUser(connection, normalized, password, nickname);
                String token = issueToken(connection, user.id());
                connection.commit();
                return buildAuthResponse(user, token);
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            if ("23000".equals(error.getSQLState())) {
                throw new IllegalStateException("\u8be5\u90ae\u7bb1\u5df2\u6ce8\u518c\uff0c\u8bf7\u76f4\u63a5\u767b\u5f55\u3002");
            }
            throw databaseError(error);
        }
    }

    public Map<String, Object> loginWithPassword(String email, String password) {
        String normalized = normalizeEmail(email);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                UserRecord user = findUserByEmail(connection, normalized)
                        .orElseThrow(() -> new IllegalArgumentException("\u8d26\u53f7\u4e0d\u5b58\u5728\uff0c\u8bf7\u5148\u6ce8\u518c\u3002"));
                if (!verifyPassword(password == null ? "" : password, user.passwordHash())) {
                    throw new IllegalArgumentException("\u90ae\u7bb1\u6216\u5bc6\u7801\u9519\u8bef\u3002");
                }
                markLogin(connection, user.id());
                String token = issueToken(connection, user.id());
                connection.commit();
                return buildAuthResponse(user, token);
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> loginWithCode(String email, String code) {
        String normalized = normalizeEmail(email);

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                UserRecord user = findUserByEmail(connection, normalized)
                        .orElseThrow(() -> new IllegalArgumentException("\u8d26\u53f7\u4e0d\u5b58\u5728\uff0c\u8bf7\u5148\u6ce8\u518c\u3002"));
                consumeVerificationCode(connection, normalized, code);
                markLogin(connection, user.id());
                String token = issueToken(connection, user.id());
                connection.commit();
                return buildAuthResponse(user, token);
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public void logout(String token) {
        if (token == null || token.isBlank()) return;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM auth_sessions WHERE token_hash = ?")) {
            statement.setString(1, sha256Hex(token.trim()));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Optional<Map<String, Object>> resolveMe(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String tokenHash = sha256Hex(token.trim());
        Instant now = Instant.now();

        try (Connection connection = openConnection()) {
            cleanupExpiredSessions(connection, now);
            String sql = """
                    SELECT u.id, u.email, u.nickname, u.password_hash, u.created_at, s.expires_at
                    FROM auth_sessions s
                    JOIN users u ON u.id = s.user_id
                    WHERE s.token_hash = ? AND s.expires_at > ?
                    LIMIT 1
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tokenHash);
                statement.setTimestamp(2, Timestamp.from(now));
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) return Optional.empty();

                    UserRecord user = readUser(rs);
                    Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
                    touchSession(connection, tokenHash, now);

                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("user", userView(user));
                    out.put("expiresAt", expiresAt.toString());
                    return Optional.of(out);
                }
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Optional<String> resolveUserId(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String tokenHash = sha256Hex(token.trim());
        Instant now = Instant.now();

        try (Connection connection = openConnection()) {
            cleanupExpiredSessions(connection, now);
            return findUserByTokenHash(connection, tokenHash, now)
                    .map(user -> String.valueOf(user.id()));
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    public Map<String, Object> updateProfile(String token, String nickname) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("\u8bf7\u5148\u767b\u5f55\u8d26\u53f7\u3002");
        }
        String normalizedNickname = normalizeNickname(nickname);
        String tokenHash = sha256Hex(token.trim());
        Instant now = Instant.now();

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                cleanupExpiredSessions(connection, now);
                UserRecord current = findUserByTokenHash(connection, tokenHash, now)
                        .orElseThrow(() -> new IllegalArgumentException("\u767b\u5f55\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u767b\u5f55\u3002"));
                updateNickname(connection, current.id(), normalizedNickname);
                touchSession(connection, tokenHash, now);
                UserRecord updated = findUserById(connection, current.id())
                        .orElseThrow(() -> new IllegalStateException("\u7528\u6237\u4fe1\u606f\u66f4\u65b0\u540e\u672a\u627e\u5230\u3002"));
                connection.commit();

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("user", userView(updated));
                return out;
            } catch (SQLException | RuntimeException error) {
                rollbackQuietly(connection);
                throw error;
            }
        } catch (SQLException error) {
            throw databaseError(error);
        }
    }

    private Connection openConnection() throws SQLException {
        Bishe10Properties.Auth.Database db = properties.getAuth().getDb();
        if (db.getUrl() == null || db.getUrl().isBlank()) {
            throw new IllegalStateException("\u8bf7\u5148\u914d\u7f6e MySQL \u8fde\u63a5\u5730\u5740\u3002");
        }
        if (dbConfigLogged.compareAndSet(false, true)) {
            LOGGER.info("auth: mysql target={} user={} cwd={}", describeDbTarget(db.getUrl()), db.getUsername(), System.getProperty("user.dir"));
        }
        return DriverManager.getConnection(db.getUrl(), db.getUsername(), db.getPassword());
    }

    private void enforceCooldown(Connection connection, String email, Instant now, int cooldownSeconds) throws SQLException {
        String sql = """
                SELECT created_at
                FROM email_verification_codes
                WHERE email = ? AND purpose = 'auth'
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return;
                Instant createdAt = rs.getTimestamp("created_at").toInstant();
                long elapsedSeconds = Duration.between(createdAt, now).toSeconds();
                if (elapsedSeconds < cooldownSeconds) {
                    long waitSeconds = cooldownSeconds - elapsedSeconds;
                    throw new IllegalStateException("\u9a8c\u8bc1\u7801\u53d1\u9001\u8fc7\u4e8e\u9891\u7e41\uff0c\u8bf7 " + waitSeconds + " \u79d2\u540e\u518d\u8bd5\u3002");
                }
            }
        }
    }

    private long insertVerificationCode(Connection connection, String email, String codeHash, Instant now, int ttlSeconds) throws SQLException {
        String sql = """
                INSERT INTO email_verification_codes (email, code_hash, purpose, expires_at, created_at)
                VALUES (?, ?, 'auth', ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, codeHash);
            statement.setTimestamp(3, Timestamp.from(now.plusSeconds(ttlSeconds)));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return 0L;
    }

    private void deleteVerificationCode(Connection connection, long codeId) throws SQLException {
        if (codeId <= 0) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM email_verification_codes WHERE id = ?")) {
            statement.setLong(1, codeId);
            statement.executeUpdate();
        }
    }

    private void consumeVerificationCode(Connection connection, String email, String code) throws SQLException {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("\u8bf7\u8f93\u5165\u9a8c\u8bc1\u7801\u3002");
        }

        String codeHash = hashVerificationCode(email, code.trim());
        String sql = """
                SELECT id
                FROM email_verification_codes
                WHERE email = ?
                  AND purpose = 'auth'
                  AND code_hash = ?
                  AND used_at IS NULL
                  AND expires_at > ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            statement.setString(2, codeHash);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("\u9a8c\u8bc1\u7801\u9519\u8bef\u6216\u5df2\u8fc7\u671f\uff0c\u8bf7\u91cd\u65b0\u83b7\u53d6\u3002");
                }
                markVerificationCodeUsed(connection, rs.getLong("id"));
            }
        }
    }

    private void markVerificationCodeUsed(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE email_verification_codes SET used_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    private Optional<UserRecord> findUserByEmail(Connection connection, String email) throws SQLException {
        String sql = "SELECT id, email, nickname, password_hash, created_at FROM users WHERE email = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readUser(rs));
            }
        }
    }

    private Optional<UserRecord> findUserById(Connection connection, long userId) throws SQLException {
        String sql = "SELECT id, email, nickname, password_hash, created_at FROM users WHERE id = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readUser(rs));
            }
        }
    }

    private Optional<UserRecord> findUserByTokenHash(Connection connection, String tokenHash, Instant now) throws SQLException {
        String sql = """
                SELECT u.id, u.email, u.nickname, u.password_hash, u.created_at
                FROM auth_sessions s
                JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > ?
                LIMIT 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenHash);
            statement.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(readUser(rs));
            }
        }
    }

    private void updateNickname(Connection connection, long userId, String nickname) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET nickname = ?, updated_at = ? WHERE id = ?")) {
            statement.setString(1, nickname);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    private UserRecord insertUser(Connection connection, String email, String password, String nickname) throws SQLException {
        String resolvedNickname = nickname == null || nickname.isBlank() ? defaultNickname(email) : nickname.trim();
        String passwordHash = hashPassword(password);
        Instant now = Instant.now();
        String sql = """
                INSERT INTO users (email, nickname, password_hash, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, email);
            statement.setString(2, resolvedNickname);
            statement.setString(3, passwordHash);
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return new UserRecord(keys.getLong(1), email, resolvedNickname, passwordHash, now);
                }
            }
        }
        throw new IllegalStateException("\u7528\u6237\u521b\u5efa\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u518d\u8bd5\u3002");
    }

    private String issueToken(Connection connection, long userId) throws SQLException {
        String token = randomToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofDays(Math.max(1, properties.getAuth().getTokenTtlDays())));
        String sql = """
                INSERT INTO auth_sessions (token_hash, user_id, expires_at, created_at, last_seen_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sha256Hex(token));
            statement.setLong(2, userId);
            statement.setTimestamp(3, Timestamp.from(expiresAt));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.setTimestamp(5, Timestamp.from(now));
            statement.executeUpdate();
        }
        return token;
    }

    private void markLogin(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE users SET last_login_at = ?, updated_at = ? WHERE id = ?")) {
            Timestamp now = Timestamp.from(Instant.now());
            statement.setTimestamp(1, now);
            statement.setTimestamp(2, now);
            statement.setLong(3, userId);
            statement.executeUpdate();
        }
    }

    private void touchSession(Connection connection, String tokenHash, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE auth_sessions SET last_seen_at = ? WHERE token_hash = ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, tokenHash);
            statement.executeUpdate();
        }
    }

    private void cleanupExpiredCodes(Connection connection, Instant now) throws SQLException {
        String sql = """
                DELETE FROM email_verification_codes
                WHERE expires_at < ?
                   OR (used_at IS NOT NULL AND used_at < ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now.minus(Duration.ofHours(1))));
            statement.setTimestamp(2, Timestamp.from(now.minus(Duration.ofDays(1))));
            statement.executeUpdate();
        }
    }

    private void cleanupExpiredSessions(Connection connection, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM auth_sessions WHERE expires_at < ?")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    private UserRecord readUser(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getLong("id"),
                rs.getString("email"),
                rs.getString("nickname"),
                rs.getString("password_hash"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private void sendVerificationEmail(String email, String code, int ttlSeconds) {
        Bishe10Properties.Auth.Mail mail = properties.getAuth().getMail();
        if (!mail.isEnabled()) {
            throw new IllegalStateException("\u90ae\u4ef6\u53d1\u9001\u672a\u542f\u7528\uff0c\u8bf7\u5148\u914d\u7f6e QQ \u90ae\u7bb1 SMTP\u3002");
        }
        if (isBlank(mail.getHost()) || isBlank(mail.getUsername()) || isBlank(mail.getPassword())) {
            throw new IllegalStateException("\u8bf7\u5148\u914d\u7f6e QQ \u90ae\u7bb1 SMTP \u8d26\u53f7\u548c\u6388\u6743\u7801\u3002");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mail.getHost());
        sender.setPort(mail.getPort());
        sender.setUsername(mail.getUsername());
        sender.setPassword(mail.getPassword());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());

        Properties javaMailProperties = sender.getJavaMailProperties();
        javaMailProperties.put("mail.smtp.auth", "true");
        javaMailProperties.put("mail.smtp.ssl.enable", String.valueOf(mail.isSslEnabled()));
        javaMailProperties.put("mail.smtp.starttls.enable", String.valueOf(!mail.isSslEnabled()));
        javaMailProperties.put("mail.smtp.connectiontimeout", String.valueOf(mail.getTimeoutMs()));
        javaMailProperties.put("mail.smtp.timeout", String.valueOf(mail.getTimeoutMs()));
        javaMailProperties.put("mail.smtp.writetimeout", String.valueOf(mail.getTimeoutMs()));

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            String from = mail.resolvedFrom();
            helper.setFrom(new InternetAddress(from, mail.getSenderName(), StandardCharsets.UTF_8.name()));
            helper.setTo(email);
            helper.setSubject("\u3010Bishe10\u3011\u90ae\u7bb1\u9a8c\u8bc1\u7801");
            helper.setText(mailBody(code, ttlSeconds), false);
            sender.send(message);
            LOGGER.info("auth: sent verification code to {}", email);
        } catch (MailException error) {
            LOGGER.warn("auth: failed to send verification email to {}", email, error);
            throw new IllegalStateException("\u9a8c\u8bc1\u7801\u90ae\u4ef6\u53d1\u9001\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5 QQ SMTP \u914d\u7f6e\u3002");
        } catch (Exception error) {
            LOGGER.warn("auth: failed to build verification email to {}", email, error);
            throw new IllegalStateException("\u9a8c\u8bc1\u7801\u90ae\u4ef6\u53d1\u9001\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u90ae\u7bb1\u914d\u7f6e\u3002");
        }
    }

    private String mailBody(String code, int ttlSeconds) {
        long ttlMinutes = Math.max(1, ttlSeconds / 60);
        return "\u60a8\u597d\uff0c\n\n"
                + "\u60a8\u6b63\u5728\u4f7f\u7528 Bishe10 \u8fdb\u884c\u90ae\u7bb1\u9a8c\u8bc1\u3002\n\n"
                + "\u9a8c\u8bc1\u7801\uff1a" + code + "\n"
                + "\u6709\u6548\u671f\uff1a" + ttlMinutes + " \u5206\u949f\n\n"
                + "\u5982\u679c\u4e0d\u662f\u60a8\u672c\u4eba\u64cd\u4f5c\uff0c\u8bf7\u5ffd\u7565\u8fd9\u5c01\u90ae\u4ef6\u3002";
    }

    private Map<String, Object> buildAuthResponse(UserRecord user, String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", token);
        out.put("user", userView(user));
        return out;
    }

    private Map<String, Object> userView(UserRecord user) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(user.id()));
        view.put("email", user.email());
        view.put("nickname", user.nickname());
        view.put("createdAt", user.createdAt().toString());
        return view;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("\u8bf7\u8f93\u5165\u6709\u6548\u7684\u90ae\u7bb1\u5730\u5740\u3002");
        }
        String normalized = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("\u8bf7\u8f93\u5165\u6709\u6548\u7684\u90ae\u7bb1\u5730\u5740\u3002");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("\u5bc6\u7801\u81f3\u5c11 6 \u4f4d\u3002");
        }
    }

    private String defaultNickname(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : email;
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            throw new IllegalArgumentException("\u8bf7\u8f93\u5165\u6635\u79f0\u3002");
        }
        String normalized = nickname.trim();
        if (normalized.length() > 100) {
            throw new IllegalArgumentException("\u6635\u79f0\u4e0d\u80fd\u8d85\u8fc7 100 \u4e2a\u5b57\u7b26\u3002");
        }
        return normalized;
    }

    private String generateCode() {
        return String.format("%06d", random.nextInt(1_000_000));
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

    private String hashVerificationCode(String email, String code) {
        String pepper = properties.getAuth().getCodePepper();
        return sha256Hex(email + ":" + code + ":" + (pepper == null ? "" : pepper));
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
        Bishe10Properties.Auth.Database db = properties.getAuth().getDb();
        String target = describeDbTarget(db.getUrl());
        LOGGER.warn(
                "auth: database operation failed target={} user={} sqlState={} vendorCode={} message={}",
                target,
                db.getUsername(),
                error.getSQLState(),
                error.getErrorCode(),
                error.getMessage(),
                error
        );
        return new IllegalStateException("\u767b\u5f55\u6570\u636e\u5e93\u64cd\u4f5c\u5931\u8d25\uff1a\u65e0\u6cd5\u8fde\u63a5 MySQL " + target + "\uff08\u7528\u6237 " + db.getUsername() + "\uff09\u3002");
    }

    private void rollbackQuietly(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException rollbackError) {
            LOGGER.warn("auth: failed to rollback transaction", rollbackError);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String describeDbTarget(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) return "(empty)";
        String value = jdbcUrl;
        if (value.startsWith("jdbc:mysql://")) {
            value = value.substring("jdbc:mysql://".length());
        }
        int queryIndex = value.indexOf('?');
        if (queryIndex >= 0) {
            value = value.substring(0, queryIndex);
        }
        return value;
    }

    private record UserRecord(long id, String email, String nickname, String passwordHash, Instant createdAt) {
    }
}
