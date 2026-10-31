-- Line 12. No real recipient, credential or business threshold is seeded.
ALTER TABLE cps_withdrawal
    ADD COLUMN request_key VARCHAR(100) COLLATE utf8mb4_bin NULL,
    ADD COLUMN recipient_id BIGINT NULL,
    ADD COLUMN recipient_name VARCHAR(128) NULL,
    ADD COLUMN recipient_account VARCHAR(255) COLLATE utf8mb4_bin NULL,
    ADD COLUMN recipient_hash CHAR(64) COLLATE utf8mb4_bin NULL,
    ADD COLUMN review_reasons TEXT NULL,
    ADD COLUMN policy_snapshot JSON NULL,
    ADD COLUMN audit_at DATETIME(3) NULL,
    ADD COLUMN submitted_at DATETIME(3) NULL,
    ADD COLUMN completed_at DATETIME(3) NULL,
    ADD COLUMN updated_at DATETIME(3) NULL,
    ADD UNIQUE KEY uk_withdraw_request(user_id,request_key),
    ADD KEY idx_withdraw_recipient_day(recipient_hash,created_at),
    ADD KEY idx_withdraw_channel_submission(channel,submitted_at),
    ADD KEY idx_withdraw_status(status,created_at);

-- Shared admission lock also protects limits shared by several users/payees.
CREATE TABLE cps_withdraw_guard (
    id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO cps_withdraw_guard(id) VALUES(1);

CREATE TABLE cps_withdraw_policy (
    id INT NOT NULL PRIMARY KEY,
    config JSON NOT NULL,
    updated_by BIGINT NULL,
    updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO cps_withdraw_policy(id,config,updated_at) VALUES(1,'{"enabled":false}',UTC_TIMESTAMP(3));

CREATE TABLE cps_withdraw_recipient (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    channel VARCHAR(32) NOT NULL,
    recipient_name VARCHAR(128) NOT NULL,
    recipient_account VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
    recipient_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    verified BOOLEAN NOT NULL DEFAULT FALSE,
    identity_verified BOOLEAN NOT NULL DEFAULT FALSE,
    verification_ref VARCHAR(255) NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    UNIQUE KEY uk_withdraw_recipient_user_channel(user_id,channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_withdraw_audit (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    withdraw_no VARCHAR(100) NULL,
    user_id BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    from_status VARCHAR(32) NULL,
    to_status VARCHAR(32) NULL,
    operator_id BIGINT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    KEY idx_withdraw_audit_no(withdraw_no,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_withdraw_user_group (
    user_id BIGINT NOT NULL PRIMARY KEY,
    group_code VARCHAR(64) NOT NULL,
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Durable mock state survives a restart. No real payment integration is present.
CREATE TABLE cps_withdraw_mock_payment (
    withdraw_no VARCHAR(100) NOT NULL PRIMARY KEY,
    amount DECIMAL(14,2) NOT NULL,
    recipient_hash CHAR(64) COLLATE utf8mb4_bin NOT NULL,
    channel_no VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    callback_token VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Keep commission.min.withdraw as the single minimum-amount configuration.
-- All other monetary/volume thresholds stay unset until configured by operations.
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '最低提现金额（待配置）','commission.min.withdraw','','Y','system',NOW(),
       '空值保持提现关闭；沿用原配置键，由业务确认后设置，不覆盖已有值'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='commission.min.withdraw');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '模拟打款结果','cps.withdraw.mock.status','PROCESSING','Y','system',NOW(),
       '仅非生产模拟：SUCCESS/FAILED/PROCESSING/UNKNOWN/AWAITING_CONFIRMATION/TIMED_OUT'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='cps.withdraw.mock.status');
