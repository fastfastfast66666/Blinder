-- MySQL 8.0.38 schema for Bishe10 real email auth.
-- Run this file once before using the login/register endpoints.

CREATE DATABASE IF NOT EXISTS bishe10
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE bishe10;

CREATE TABLE IF NOT EXISTS users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NULL,
  email VARCHAR(191) NOT NULL,
  nickname VARCHAR(100) NOT NULL,
  phone VARCHAR(32) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'enabled',
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  last_login_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

SET @bishe10_ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN username VARCHAR(64) NULL AFTER id',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'username'
);
PREPARE bishe10_stmt FROM @bishe10_ddl;
EXECUTE bishe10_stmt;
DEALLOCATE PREPARE bishe10_stmt;

SET @bishe10_ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN phone VARCHAR(32) NULL AFTER nickname',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'phone'
);
PREPARE bishe10_stmt FROM @bishe10_ddl;
EXECUTE bishe10_stmt;
DEALLOCATE PREPARE bishe10_stmt;

SET @bishe10_ddl := (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE users ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT ''enabled'' AFTER phone',
    'SELECT 1')
  FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'users'
    AND COLUMN_NAME = 'status'
);
PREPARE bishe10_stmt FROM @bishe10_ddl;
EXECUTE bishe10_stmt;
DEALLOCATE PREPARE bishe10_stmt;

CREATE TABLE IF NOT EXISTS email_verification_codes (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  email VARCHAR(191) NOT NULL,
  code_hash CHAR(64) NOT NULL,
  purpose VARCHAR(32) NOT NULL DEFAULT 'auth',
  expires_at DATETIME(3) NOT NULL,
  used_at DATETIME(3) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_email_purpose_created (email, purpose, created_at),
  KEY idx_email_purpose_hash (email, purpose, code_hash),
  KEY idx_expires_at (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS auth_sessions (
  token_hash CHAR(64) NOT NULL,
  user_id BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3) NULL,
  PRIMARY KEY (token_hash),
  KEY idx_auth_sessions_user_id (user_id),
  KEY idx_auth_sessions_expires_at (expires_at),
  CONSTRAINT fk_auth_sessions_user
    FOREIGN KEY (user_id) REFERENCES users (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_users (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  nickname VARCHAR(100) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'enabled',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  last_login_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS admin_sessions (
  token_hash CHAR(64) NOT NULL,
  admin_id BIGINT UNSIGNED NOT NULL,
  expires_at DATETIME(3) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  last_seen_at DATETIME(3) NULL,
  PRIMARY KEY (token_hash),
  KEY idx_admin_sessions_admin_id (admin_id),
  KEY idx_admin_sessions_expires_at (expires_at),
  CONSTRAINT fk_admin_sessions_admin
    FOREIGN KEY (admin_id) REFERENCES admin_users (id)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'bishe10_app'@'localhost'
  IDENTIFIED BY 'bishe10_dev_pwd';

CREATE USER IF NOT EXISTS 'bishe10_app'@'127.0.0.1'
  IDENTIFIED BY 'bishe10_dev_pwd';

GRANT SELECT, INSERT, UPDATE, DELETE
  ON bishe10.*
  TO 'bishe10_app'@'localhost';

GRANT SELECT, INSERT, UPDATE, DELETE
  ON bishe10.*
  TO 'bishe10_app'@'127.0.0.1';

FLUSH PRIVILEGES;
