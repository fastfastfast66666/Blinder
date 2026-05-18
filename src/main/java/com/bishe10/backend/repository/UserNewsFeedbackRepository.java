package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Repository
public class UserNewsFeedbackRepository extends JdbcRepositorySupport {

    public UserNewsFeedbackRepository(Bishe10Properties properties) {
        super(properties);
    }

    public void save(String userId, String articleId, String action) throws SQLException {
        String sql = """
                INSERT INTO user_news_feedback (user_id, article_id, action, created_at, updated_at)
                VALUES (?, ?, ?, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE updated_at = NOW(3)
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            statement.setString(2, articleId);
            statement.setString(3, action);
            statement.executeUpdate();
        }
    }

    public Map<String, Set<String>> findActions(String userId, Collection<String> articleIds) throws SQLException {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (userId == null || userId.isBlank() || articleIds == null || articleIds.isEmpty()) {
            return result;
        }
        String placeholders = articleIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
        String sql = """
                SELECT article_id, action
                FROM user_news_feedback
                WHERE user_id = ? AND article_id IN (%s)
                """.formatted(placeholders);
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, userId);
            int index = 2;
            for (String articleId : articleIds) {
                statement.setString(index++, articleId);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.computeIfAbsent(rs.getString("article_id"), key -> new LinkedHashSet<>())
                            .add(rs.getString("action"));
                }
            }
        }
        return result;
    }

    public int deleteByUser(String userId) throws SQLException {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM user_news_feedback WHERE user_id = ?")) {
            statement.setString(1, userId);
            return statement.executeUpdate();
        }
    }
}
