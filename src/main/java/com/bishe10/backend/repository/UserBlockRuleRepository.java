package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.model.UserBlockRule;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class UserBlockRuleRepository extends JdbcRepositorySupport {

    public UserBlockRuleRepository(Bishe10Properties properties) {
        super(properties);
    }

    public void save(String userId, String ruleType, String ruleValue) throws SQLException {
        String sql = """
                INSERT INTO user_block_rule (user_id, rule_type, rule_value, created_at)
                VALUES (?, ?, ?, NOW(3))
                ON DUPLICATE KEY UPDATE created_at = created_at
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, ruleType);
            statement.setString(3, ruleValue);
            statement.executeUpdate();
        }
    }

    public List<UserBlockRule> findByUser(String userId) throws SQLException {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        String sql = """
                SELECT id, user_id, rule_type, rule_value, created_at
                FROM user_block_rule
                WHERE user_id = ?
                ORDER BY created_at DESC
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            try (ResultSet rs = statement.executeQuery()) {
                List<UserBlockRule> items = new ArrayList<>();
                while (rs.next()) {
                    items.add(new UserBlockRule(
                            rs.getLong("id"),
                            rs.getString("user_id"),
                            rs.getString("rule_type"),
                            rs.getString("rule_value"),
                            toOffsetDateTime(rs.getTimestamp("created_at"))
                    ));
                }
                return items;
            }
        }
    }

    public int deleteByUser(String userId) throws SQLException {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM user_block_rule WHERE user_id = ?")) {
            statement.setString(1, userId);
            return statement.executeUpdate();
        }
    }
}
