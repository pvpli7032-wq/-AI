-- Contract schema only. Existing records retain their original meaning.
ALTER TABLE sys_order_commission
    ADD COLUMN sub_order_no VARCHAR(100) NOT NULL DEFAULT '' COMMENT '子订单号，无子单为空串',
    ADD COLUMN received_time DATETIME NULL,
    ADD COLUMN raw_status VARCHAR(128) NULL COMMENT '联盟原始状态',
    ADD COLUMN pid VARCHAR(128) NULL COMMENT '推广位',
    ADD COLUMN attribution_token VARCHAR(255) NULL COMMENT '归因标识',
    ADD COLUMN paid_time DATETIME NULL COMMENT '付款时间',
    ADD COLUMN line_status VARCHAR(32) NULL COMMENT '订单行状态 OrderStatus',
    -- settle_time 已由 V1 提供，保留原字段及含义。
    DROP INDEX uk_order_platform,
    ADD UNIQUE KEY uk_order_platform_sub (platform_type, order_id, sub_order_no);
ALTER TABLE sys_wechat_user
    ADD COLUMN invitation_code VARCHAR(64) NULL,
    ADD COLUMN inviter_user_id BIGINT NULL,
    ADD COLUMN phone VARCHAR(20) NULL,
    ADD COLUMN risk_level VARCHAR(32) NULL,
    ADD UNIQUE KEY uk_wechat_invitation_code (invitation_code),
    ADD KEY idx_wechat_inviter (inviter_user_id);
ALTER TABLE sys_user ADD COLUMN risk_level VARCHAR(32) NULL;
ALTER TABLE sys_agent_commission_account
    ADD COLUMN withdrawing_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    MODIFY COLUMN total_earned DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '累计收益',
    MODIFY COLUMN self_earned DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '自购累计收益',
    MODIFY COLUMN promotion_earned DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '推广累计收益',
    MODIFY COLUMN withdrawn DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '已提现金额',
    MODIFY COLUMN balance DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额',
    MODIFY COLUMN frozen_amount DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '冻结金额';
-- V91 已提供 idempotency_key 的唯一键，V100 的旧业务唯一键保持不变。
ALTER TABLE sys_commission_flow
    MODIFY COLUMN amount DECIMAL(14,2) NOT NULL COMMENT '变动金额',
    MODIFY COLUMN balance_before DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '变动前余额',
    MODIFY COLUMN balance_after DECIMAL(14,2) NOT NULL DEFAULT 0.00 COMMENT '变动后余额',
    ADD COLUMN debit_account VARCHAR(32) NULL COMMENT '借方账户',
    ADD COLUMN credit_account VARCHAR(32) NULL COMMENT '贷方账户',
    ADD COLUMN business_type VARCHAR(64) NULL COMMENT '业务类型',
    ADD COLUMN business_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '业务ID',
    ADD COLUMN account VARCHAR(32) NULL COMMENT '分录账户',
    ADD CONSTRAINT chk_flow_business_account CHECK ((business_type IS NULL AND business_id IS NULL AND account IS NULL) OR (business_type IS NOT NULL AND business_id IS NOT NULL AND account IS NOT NULL)),
    ADD UNIQUE KEY uk_flow_business_account (business_type,business_id,account);
ALTER TABLE sys_union_platform ADD COLUMN pid_type VARCHAR(20) NULL COMMENT 'tracking/default/blocked/redpacket';

CREATE TABLE cps_pid_lease (
 id BIGINT NOT NULL AUTO_INCREMENT, platform VARCHAR(20) NOT NULL, pid VARCHAR(128) NOT NULL,
 item_id VARCHAR(128) NOT NULL, user_id BIGINT NOT NULL, start_at DATETIME(3) NOT NULL,
 expire_at DATETIME(3) NOT NULL, PRIMARY KEY(id), KEY idx_pid_item_expiry(pid,item_id,expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_query_log (
 id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, platform VARCHAR(20), raw_hash VARCHAR(128),
 item_id VARCHAR(128), result VARCHAR(32) NOT NULL, commission_rate DECIMAL(9,4),
 shown_rebate DECIMAL(14,2), pid VARCHAR(128), created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(id), KEY idx_query_user_time(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_order_claim (
 id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, platform VARCHAR(20) NOT NULL,
 order_no VARCHAR(100) NOT NULL, status VARCHAR(32) NOT NULL, created_at DATETIME(3) NOT NULL,
 PRIMARY KEY(id), KEY idx_claim_order(platform,order_no), KEY idx_claim_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_attribution (
 id BIGINT NOT NULL AUTO_INCREMENT, order_line_id BIGINT NOT NULL, user_id BIGINT,
 method VARCHAR(32) NOT NULL, confidence VARCHAR(32), decided_by BIGINT, is_current BOOLEAN NOT NULL,
 created_at DATETIME(3) NOT NULL, PRIMARY KEY(id), KEY idx_attribution_order(order_line_id,is_current)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_order_raw (
 id BIGINT NOT NULL AUTO_INCREMENT, platform VARCHAR(20) NOT NULL, payload JSON NOT NULL,
 fetched_at DATETIME(3) NOT NULL, content_hash VARCHAR(128) NOT NULL,
 PRIMARY KEY(id), KEY idx_raw_hash(platform,content_hash), KEY idx_raw_fetched(fetched_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_order_event (
 id BIGINT NOT NULL AUTO_INCREMENT, event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 order_line_id BIGINT NOT NULL, from_status VARCHAR(32), to_status VARCHAR(32) NOT NULL,
 previous_status VARCHAR(32) NULL COMMENT '维权前状态，恢复时必须与已保存状态一致',
 recovered_amount DECIMAL(14,2) NULL COMMENT '追回金额（含部分或全部处罚）',
 source VARCHAR(32) NOT NULL, raw_id BIGINT,
 created_at DATETIME(3) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_order_event_key(event_key), KEY idx_event_order(order_line_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_rule (
 id BIGINT NOT NULL AUTO_INCREMENT, type VARCHAR(32) NOT NULL, name VARCHAR(128) NOT NULL,
 priority INT NOT NULL, status VARCHAR(32) NOT NULL, PRIMARY KEY(id), KEY idx_rule_type_status(type,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_rule_version (
 id BIGINT NOT NULL AUTO_INCREMENT, rule_id BIGINT NOT NULL, version BIGINT NOT NULL,
 config JSON NOT NULL, effective_at DATETIME(3) NOT NULL, created_by BIGINT NOT NULL,
 PRIMARY KEY(id), UNIQUE KEY uk_rule_version(rule_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_reward_progress (
 id BIGINT NOT NULL AUTO_INCREMENT, user_id BIGINT NOT NULL, rule_id BIGINT NOT NULL,
 valid_count BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(id), UNIQUE KEY uk_progress_user_rule(user_id,rule_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_settle_item (
 id BIGINT NOT NULL AUTO_INCREMENT, order_line_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 type VARCHAR(32) NOT NULL, rule_version_id BIGINT, est_amount DECIMAL(14,2),
 final_amount DECIMAL(14,2), status VARCHAR(32) NOT NULL, unfreeze_at DATETIME(3), paused_at DATETIME(3),
 PRIMARY KEY(id), UNIQUE KEY uk_settle_order_user_type(order_line_id,user_id,type), KEY idx_settle_unfreeze(status,unfreeze_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_withdrawal (
 id BIGINT NOT NULL AUTO_INCREMENT, withdraw_no VARCHAR(100) NOT NULL, user_id BIGINT NOT NULL,
 amount DECIMAL(14,2) NOT NULL, fee DECIMAL(14,2) NOT NULL DEFAULT 0.00, status VARCHAR(32) NOT NULL,
 channel VARCHAR(32), channel_no VARCHAR(128), audit_by BIGINT, fail_reason VARCHAR(500),
 created_at DATETIME(3) NOT NULL, PRIMARY KEY(id), UNIQUE KEY uk_withdraw_no(withdraw_no), KEY idx_withdraw_user(user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_recon_batch (
 id BIGINT NOT NULL AUTO_INCREMENT, platform VARCHAR(20) NOT NULL, period VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL, PRIMARY KEY(id), KEY idx_recon_period(platform,period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_recon_diff (
 id BIGINT NOT NULL AUTO_INCREMENT, batch_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL,
 ref_id VARCHAR(128) NOT NULL, amount DECIMAL(14,2), status VARCHAR(32) NOT NULL, resolved_by BIGINT,
 PRIMARY KEY(id), KEY idx_diff_batch(batch_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_punish_import (
 id BIGINT NOT NULL AUTO_INCREMENT, platform VARCHAR(20) NOT NULL, file VARCHAR(500) NOT NULL,
 operator BIGINT NOT NULL, `lines` INT NOT NULL, PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
