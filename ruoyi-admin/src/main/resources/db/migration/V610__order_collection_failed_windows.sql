-- 仅记录订单采集窗口、续页游标和非敏感错误码；不记录请求、响应、凭据或异常原文。
CREATE TABLE cps_order_collection_retry (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    dedupe_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    platform VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    window_from DATETIME(6) NOT NULL,
    window_to DATETIME(6) NOT NULL,
    page_cursor VARCHAR(512) NULL,
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_order_retry_dedupe (dedupe_key),
    KEY idx_order_retry_ready (status, next_attempt_at, id),
    KEY idx_order_retry_lease (status, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
