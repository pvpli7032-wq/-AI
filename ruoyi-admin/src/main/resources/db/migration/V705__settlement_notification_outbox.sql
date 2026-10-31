-- MySQL 8. Delivery is intentionally PENDING until an idempotent channel is installed.
-- Private internal routing only: no raw order numbers, business keys, provider bodies or PII.
CREATE TABLE cps_settlement_notification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_key_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    delivery_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BIGINT NOT NULL,
    order_line_id BIGINT NULL COMMENT 'Internal surrogate only, not a raw platform order number',
    reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    loss_amount DECIMAL(18,2) NOT NULL,
    balance_after DECIMAL(18,2) NOT NULL COMMENT 'Ledger snapshot; may be negative',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    attempts BIGINT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(32) CHARACTER SET ascii NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cps_notification_business (business_key_hash),
    UNIQUE KEY uk_cps_notification_delivery (delivery_key),
    KEY ix_cps_notification_pending (status, next_attempt_at, id),
    KEY ix_cps_notification_lease (status, lease_until, id),
    CONSTRAINT ck_cps_notification_loss CHECK (loss_amount >= 0),
    CONSTRAINT ck_cps_notification_reason CHECK (reason IN
        ('BINDING_FAILURE', 'REBATE_INVALID', 'REFUND', 'WHOLE_MONTH_PENALTY')),
    CONSTRAINT ck_cps_notification_status CHECK (status IN ('PENDING', 'CLAIMED', 'SENT')),
    CONSTRAINT ck_cps_notification_claim CHECK (
        (status = 'CLAIMED' AND claim_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'CLAIMED' AND claim_token IS NULL AND lease_until IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
