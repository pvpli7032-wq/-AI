-- Preserve unknown ownership and absent monetary input without manufacturing values.
ALTER TABLE sys_order_commission
    MODIFY COLUMN agent_user_id BIGINT NULL COMMENT '下单代理用户ID，NULL表示未归因',
    ADD COLUMN cps_status_second BIGINT NULL,
    ADD COLUMN cps_status_nano INT NULL,
    ADD COLUMN cps_dispute_previous VARCHAR(32) NULL,
    ADD COLUMN cps_paid_second BIGINT NULL,
    ADD COLUMN cps_paid_nano INT NULL,
    ADD COLUMN cps_settled_second BIGINT NULL,
    ADD COLUMN cps_settled_nano INT NULL,
    ADD COLUMN cps_fast_settlement BOOLEAN NULL COMMENT '支付至结算不足24小时，NULL表示时间未知或异常';

-- MySQL JSON reserializes payloads, so retain the exact received UTF-8 text too.
ALTER TABLE cps_order_raw
    ADD COLUMN payload_text LONGTEXT NULL;

CREATE TABLE cps_order_ingest_exception (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    raw_id BIGINT NOT NULL,
    order_line_id BIGINT NULL,
    event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_ingest_exception_raw(raw_id), KEY idx_ingest_exception_order(order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_order_event_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    line_snapshot JSON NOT NULL,
    occurred_second BIGINT NOT NULL,
    occurred_nano INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attribution_done BOOLEAN NOT NULL DEFAULT FALSE,
    settlement_done BOOLEAN NOT NULL DEFAULT FALSE,
    claim_token VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(3) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_cps_outbox_event(event_key),
    KEY idx_cps_outbox_pending(status,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
