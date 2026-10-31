-- Settlement state is independent of ingestion's already-updated line_status.
-- No historical balances/flows are migrated or rewritten by this migration.
ALTER TABLE cps_settle_item
    ADD COLUMN fund_phase VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN recovered_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN freeze_remaining_millis BIGINT NULL,
    ADD COLUMN freeze_anchor DATETIME(3) NULL,
    ADD COLUMN freeze_paused BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE cps_settlement_position (
    order_line_id BIGINT NOT NULL,
    buyer_user_id BIGINT NULL,
    order_status VARCHAR(32) NULL,
    previous_status VARCHAR(32) NULL,
    original_commission DECIMAL(20,8) NULL,
    recovered_commission DECIMAL(20,8) NOT NULL DEFAULT 0,
    terms_json JSON NULL,
    actual_terms_json JSON NULL,
    reported_commission DECIMAL(20,8) NULL,
    last_event_at DATETIME(3) NULL,
    referral_manual BOOLEAN NOT NULL DEFAULT FALSE,
    invitation_evidence VARCHAR(255) NULL,
    PRIMARY KEY (order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_settlement_receipt (
    event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    order_line_id BIGINT NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (event_key), KEY idx_settlement_receipt_order(order_line_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_settlement_manual (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_line_id BIGINT NOT NULL,
    event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id), UNIQUE KEY uk_settlement_manual_event(order_line_id,event_key,reason)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
