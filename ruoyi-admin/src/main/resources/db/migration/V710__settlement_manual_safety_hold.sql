-- A missing cumulative loss amount must commit a durable hold before any funding or debit.
-- Only an explicitly reviewed correction may resolve a hold; ordinary events never clear it.
ALTER TABLE cps_settlement_position
    ADD COLUMN manual_hold BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE cps_settlement_hold_event (
    event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_line_id BIGINT NOT NULL,
    reason VARCHAR(255) NOT NULL,
    event_json JSON NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_key),
    KEY idx_settlement_hold_order(order_line_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
