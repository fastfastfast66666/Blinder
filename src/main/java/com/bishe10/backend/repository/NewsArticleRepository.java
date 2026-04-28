package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;
import com.bishe10.backend.model.NewsArticle;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class NewsArticleRepository extends JdbcRepositorySupport {

    public NewsArticleRepository(Bishe10Properties properties) {
        super(properties);
    }

    public void upsertAll(List<NewsArticle> articles) throws SQLException {
        if (articles == null || articles.isEmpty()) {
            return;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                for (NewsArticle article : articles) {
                    upsert(connection, article);
                }
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        }
    }

    public void upsert(NewsArticle article) throws SQLException {
        try (Connection connection = openConnection()) {
            upsert(connection, article);
        }
    }

    public Optional<NewsArticle> findById(String articleId) throws SQLException {
        if (articleId == null || articleId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
                SELECT article_id, title, summary, content, url, source, city, province,
                       category, tags, publish_time, fetch_scope, content_hash
                FROM news_article
                WHERE article_id = ?
                LIMIT 1
                """;
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, articleId);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readArticle(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<NewsArticle> findRecentByScope(String fetchScope, String city, String province, int limit) throws SQLException {
        int effectiveLimit = Math.max(1, limit);
        String normalizedScope = fetchScope == null ? "" : fetchScope.trim().toUpperCase();
        StringBuilder sql = new StringBuilder("""
                SELECT article_id, title, summary, content, url, source, city, province,
                       category, tags, publish_time, fetch_scope, content_hash
                FROM news_article
                WHERE fetch_scope = ?
                  AND source NOT IN ('SYSTEM_TEMPLATE', 'AI_GENERATED')
                """);
        List<String> params = new ArrayList<>();
        params.add(normalizedScope);

        if ("CITY".equals(normalizedScope) && city != null && !city.isBlank()) {
            sql.append(" AND city = ?");
            params.add(city.trim());
        } else if ("PROVINCE".equals(normalizedScope) && province != null && !province.isBlank()) {
            sql.append(" AND province = ?");
            params.add(province.trim());
        } else if ("INTEREST".equals(normalizedScope) && city != null && !city.isBlank()) {
            sql.append(" AND (city = ? OR province = ? OR fetch_scope = 'INTEREST')");
            params.add(city.trim());
            params.add(province == null ? "" : province.trim());
        }

        sql.append(" ORDER BY COALESCE(publish_time, updated_at) DESC, updated_at DESC LIMIT ?");

        List<NewsArticle> items = new ArrayList<>();
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                statement.setString(i + 1, params.get(i));
            }
            statement.setInt(params.size() + 1, effectiveLimit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(readArticle(rs));
                }
            }
        }
        return items;
    }

    private void upsert(Connection connection, NewsArticle article) throws SQLException {
        String sql = """
                INSERT INTO news_article (
                  article_id, title, summary, content, url, source, city, province,
                  category, tags, publish_time, fetch_scope, content_hash, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE
                  title = VALUES(title),
                  summary = VALUES(summary),
                  content = VALUES(content),
                  url = VALUES(url),
                  source = VALUES(source),
                  city = VALUES(city),
                  province = VALUES(province),
                  category = VALUES(category),
                  tags = VALUES(tags),
                  publish_time = VALUES(publish_time),
                  fetch_scope = VALUES(fetch_scope),
                  content_hash = VALUES(content_hash),
                  updated_at = NOW(3)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, article.articleId());
            statement.setString(2, article.title());
            statement.setString(3, article.summary());
            statement.setString(4, article.content());
            statement.setString(5, article.url());
            statement.setString(6, article.source());
            statement.setString(7, article.city());
            statement.setString(8, article.province());
            statement.setString(9, article.category());
            statement.setString(10, joinTags(article.tags()));
            statement.setTimestamp(11, toTimestamp(article.publishTime()));
            statement.setString(12, article.fetchScope());
            statement.setString(13, article.contentHash());
            statement.executeUpdate();
        }
    }

    private NewsArticle readArticle(ResultSet rs) throws SQLException {
        OffsetDateTime publishTime = toOffsetDateTime(rs.getTimestamp("publish_time"));
        return new NewsArticle(
                rs.getString("article_id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getString("content"),
                rs.getString("url"),
                rs.getString("source"),
                rs.getString("city"),
                rs.getString("province"),
                rs.getString("category"),
                splitTags(rs.getString("tags")),
                publishTime,
                rs.getString("fetch_scope"),
                rs.getString("content_hash"),
                isSyntheticSource(rs.getString("source"))
        );
    }

    private boolean isSyntheticSource(String source) {
        return "SYSTEM_TEMPLATE".equals(source) || "AI_GENERATED".equals(source);
    }
}
