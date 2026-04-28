package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.model.UserInterestProfile;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class UserInterestProfileRepository extends JdbcRepositorySupport {

    public UserInterestProfileRepository(Bishe10Properties properties) {
        super(properties);
    }

    public List<UserInterestProfile> findByUser(String userId) throws SQLException {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT id, user_id, interest_type, interest_value, weight,
                       positive_count, negative_count, updated_at
                FROM user_interest_profile
                WHERE user_id = ?
                ORDER BY weight DESC, updated_at DESC
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UserInterestProfile> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(readProfile(rs));
                }
                return items;
            }
        }
    }

    public List<String> findTopPositiveValues(String userId, String type, int limit) throws SQLException {
        String sql = """
                SELECT interest_value
                FROM user_interest_profile
                WHERE user_id = ? AND interest_type = ? AND weight > 0
                ORDER BY weight DESC, positive_count DESC, updated_at DESC
                LIMIT ?
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, type);
            statement.setInt(3, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rs.next()) {
                    values.add(rs.getString("interest_value"));
                }
                return values;
            }
        }
    }

    public UserInterestProfile applyDelta(
            String userId,
            String type,
            String value,
            double delta,
            int positiveDelta,
            int negativeDelta
    ) throws SQLException {
        String sql = """
                INSERT INTO user_interest_profile (
                  user_id, interest_type, interest_value, weight,
                  positive_count, negative_count, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE
                  weight = GREATEST(-3.0, LEAST(3.0, weight + VALUES(weight))),
                  positive_count = positive_count + VALUES(positive_count),
                  negative_count = negative_count + VALUES(negative_count),
                  updated_at = NOW(3)
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, type);
            statement.setString(3, value);
            statement.setDouble(4, clamp(delta));
            statement.setInt(5, Math.max(0, positiveDelta));
            statement.setInt(6, Math.max(0, negativeDelta));
            statement.executeUpdate();
        }
        return findOne(userId, type, value).orElse(new UserInterestProfile(0, userId, type, value, clamp(delta), positiveDelta, negativeDelta, null));
    }

    private Optional<UserInterestProfile> findOne(String userId, String type, String value) throws SQLException {
        String sql = """
                SELECT id, user_id, interest_type, interest_value, weight,
                       positive_count, negative_count, updated_at
                FROM user_interest_profile
                WHERE user_id = ? AND interest_type = ? AND interest_value = ?
                LIMIT 1
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, type);
            statement.setString(3, value);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readProfile(rs));
                }
            }
        }
        return Optional.empty();
    }

    private UserInterestProfile readProfile(ResultSet rs) throws SQLException {
        return new UserInterestProfile(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("interest_type"),
                rs.getString("interest_value"),
                rs.getDouble("weight"),
                rs.getInt("positive_count"),
                rs.getInt("negative_count"),
                toOffsetDateTime(rs.getTimestamp("updated_at"))
        );
    }

    private double clamp(double value) {
        return Math.max(-3.0, Math.min(3.0, value));
    }
}
