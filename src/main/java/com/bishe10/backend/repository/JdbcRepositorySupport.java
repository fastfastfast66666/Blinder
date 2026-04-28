package com.bishe10.backend.repository;

import com.bishe10.backend.config.Bishe10Properties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

abstract class JdbcRepositorySupport {

    private final Bishe10Properties properties;

    JdbcRepositorySupport(Bishe10Properties properties) {
        this.properties = properties;
    }

    protected Connection openConnection() throws SQLException {
        Bishe10Properties.Auth.Database db = properties.getAuth().getDb();
        if (db.getUrl() == null || db.getUrl().isBlank()) {
            throw new IllegalStateException("请先配置 MySQL 连接地址。");
        }
        return DriverManager.getConnection(db.getUrl(), db.getUsername(), db.getPassword());
    }

    protected java.sql.Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : java.sql.Timestamp.from(value.toInstant());
    }

    protected OffsetDateTime toOffsetDateTime(java.sql.Timestamp value) {
        if (value == null) {
            return null;
        }
        return value.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    protected String joinTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return "";
        }
        return tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.joining(","));
    }

    protected List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .toList();
    }
}
