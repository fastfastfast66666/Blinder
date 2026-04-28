-- MySQL schema patch for personalized news recommendation.
-- Run after the base bishe10 database has been created.

USE bishe10;

CREATE TABLE IF NOT EXISTS news_article (
  article_id VARCHAR(64) NOT NULL,
  title VARCHAR(512) NOT NULL,
  summary TEXT NULL,
  content TEXT NULL,
  url VARCHAR(1024) NULL,
  source VARCHAR(128) NULL,
  city VARCHAR(64) NULL,
  province VARCHAR(64) NULL,
  category VARCHAR(64) NULL,
  tags VARCHAR(1024) NULL,
  publish_time DATETIME(3) NULL,
  fetch_scope VARCHAR(32) NULL,
  content_hash VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (article_id),
  KEY idx_news_article_city_publish (city, publish_time),
  KEY idx_news_article_province_publish (province, publish_time),
  KEY idx_news_article_scope_publish (fetch_scope, publish_time),
  KEY idx_news_article_category (category),
  KEY idx_news_article_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_news_feedback (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  article_id VARCHAR(64) NOT NULL,
  action VARCHAR(32) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_article_action (user_id, article_id, action),
  KEY idx_user_news_feedback_user (user_id),
  KEY idx_user_news_feedback_article (article_id),
  KEY idx_user_news_feedback_user_action (user_id, action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_interest_profile (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  interest_type VARCHAR(32) NOT NULL,
  interest_value VARCHAR(128) NOT NULL,
  weight DOUBLE NOT NULL DEFAULT 0,
  positive_count INT NOT NULL DEFAULT 0,
  negative_count INT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_interest (user_id, interest_type, interest_value),
  KEY idx_user_interest_user (user_id),
  KEY idx_user_interest_type_weight (user_id, interest_type, weight)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS user_block_rule (
  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  user_id VARCHAR(64) NOT NULL,
  rule_type VARCHAR(32) NOT NULL,
  rule_value VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_block_rule (user_id, rule_type, rule_value),
  KEY idx_user_block_rule_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS news_source_config (
  source_key VARCHAR(64) NOT NULL,
  source_name VARCHAR(128) NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  endpoint VARCHAR(1024) NULL,
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  priority INT NOT NULL DEFAULT 100,
  description VARCHAR(512) NULL,
  last_fetch_at DATETIME(3) NULL,
  last_status VARCHAR(32) NULL,
  last_message VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (source_key),
  KEY idx_news_source_enabled_priority (enabled, priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO news_source_config (
  source_key, source_name, source_type, endpoint, enabled, priority, description, last_status, last_message
) VALUES
  ('BAIDU', '百度新闻搜索', 'BAIDU_SEARCH', 'https://www.baidu.com/s', 1, 10, '通过百度新闻搜索页抓取城市、交通、民生等关键词新闻。', 'INIT', '系统默认新闻源'),
  ('SOGOU', '搜狗新闻搜索', 'SOGOU_SEARCH', 'https://news.sogou.com/news', 1, 20, '通过搜狗新闻搜索页补充城市和全国热点新闻。', 'INIT', '系统默认新闻源'),
  ('CHINANEWS_SCROLL', '中国新闻网滚动新闻', 'RSS', 'https://www.chinanews.com.cn/rss/scroll-news.xml', 1, 30, '中国新闻网滚动新闻 RSS，适合作为全国热点和实时快讯来源。', 'INIT', '系统默认新闻源'),
  ('CHINANEWS_CHINA', '中国新闻网时政新闻', 'RSS', 'https://www.chinanews.com.cn/rss/china.xml', 1, 40, '中国新闻网时政频道 RSS。', 'INIT', '系统默认新闻源'),
  ('CHINANEWS_SOCIETY', '中国新闻网社会新闻', 'RSS', 'https://www.chinanews.com.cn/rss/society.xml', 1, 50, '中国新闻网社会频道 RSS。', 'INIT', '系统默认新闻源'),
  ('PEOPLE_SOCIETY', '人民网社会新闻', 'RSS', 'http://www.people.com.cn/rss/society.xml', 1, 60, '人民网社会新闻 RSS，适合民生服务和全国热点兜底。', 'INIT', '系统默认新闻源')
ON DUPLICATE KEY UPDATE
  source_name = VALUES(source_name),
  source_type = VALUES(source_type),
  endpoint = VALUES(endpoint),
  priority = VALUES(priority),
  description = VALUES(description),
  updated_at = NOW(3);
