-- Line 9: durable PID allocation, append-only attribution, claims and recovery.
-- No promotion slots or business thresholds are invented by this migration.
-- Preserve millisecond payment evidence when comparing it with lease/token starts.
ALTER TABLE sys_order_commission MODIFY COLUMN paid_time DATETIME(3) NULL COMMENT '付款时间';
CREATE TABLE cps_pid_registry (
 platform VARCHAR(20) NOT NULL, pid VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 pid_type VARCHAR(20) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 updated_by BIGINT NOT NULL, updated_at DATETIME(3) NOT NULL,
 PRIMARY KEY(platform,pid), KEY idx_pid_registry_type(platform,pid_type,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_pid_pool_mutex (platform VARCHAR(20) NOT NULL PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO cps_pid_pool_mutex VALUES ('TBK'),('JD'),('PDD');
CREATE TABLE cps_pid_item_mutex (
 platform VARCHAR(20) NOT NULL, item_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY(platform,item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE cps_pid_lease
 MODIFY COLUMN pid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 MODIFY COLUMN item_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 ADD KEY idx_lease_allocation(platform,item_id,expire_at,user_id);
CREATE TABLE cps_pid_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, platform VARCHAR(20) NOT NULL,
 pid VARCHAR(128) NOT NULL, previous_type VARCHAR(20), new_type VARCHAR(20) NOT NULL,
 enabled BOOLEAN NOT NULL, operator_id BIGINT NOT NULL, reason VARCHAR(500) NOT NULL,
 created_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_attribution_token (
 token CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 platform VARCHAR(20) NOT NULL, item_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL, issued_at DATETIME(3) NOT NULL,
 KEY idx_token_user(platform,user_id,issued_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_claim_mutex (
 platform VARCHAR(20) NOT NULL, order_no VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 PRIMARY KEY(platform,order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE cps_order_claim ADD COLUMN expires_at DATETIME(3),
 ADD COLUMN updated_at DATETIME(3), ADD COLUMN reason VARCHAR(128),
 ADD KEY idx_claim_candidates(platform,order_no,status,expires_at);
ALTER TABLE cps_attribution ADD COLUMN priority INT NOT NULL DEFAULT 0,
 ADD COLUMN reason VARCHAR(128), ADD COLUMN previous_id BIGINT,
 ADD KEY idx_attribution_history(order_line_id,id);
-- Historical decisions are never UPDATEd, including their legacy is_current bit.
-- This separate pointer is the authoritative current decision, not is_current.
CREATE TABLE cps_attribution_current (
 order_line_id BIGINT NOT NULL PRIMARY KEY, attribution_id BIGINT NOT NULL,
 UNIQUE KEY uk_attribution_pointer(attribution_id)
) ENGINE=InnoDB;
INSERT INTO cps_attribution_current(order_line_id,attribution_id)
 SELECT order_line_id,MAX(id) FROM cps_attribution GROUP BY order_line_id;
CREATE TABLE cps_attribution_inviter_snapshot (
 attribution_id BIGINT NOT NULL PRIMARY KEY, user_id BIGINT,
 inviter_user_id BIGINT, paid_at DATETIME(3)
) ENGINE=InnoDB;
CREATE TABLE cps_attribution_candidate (
 order_line_id BIGINT NOT NULL, user_id BIGINT NOT NULL, query_log_id BIGINT NOT NULL,
 created_at DATETIME(3) NOT NULL, PRIMARY KEY(order_line_id,user_id,query_log_id)
) ENGINE=InnoDB;
CREATE TABLE cps_unclaimed_order (
 order_line_id BIGINT NOT NULL PRIMARY KEY, status VARCHAR(32) NOT NULL,
 reason VARCHAR(128) NOT NULL, first_seen_at DATETIME(3) NOT NULL,
 expires_at DATETIME(3) NOT NULL, resolved_at DATETIME(3),
 KEY idx_unclaimed_expiry(status,expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_attribution_review (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_line_id BIGINT NOT NULL,
 proposed_user_id BIGINT, reason VARCHAR(128) NOT NULL, operator_id BIGINT,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING', detail VARCHAR(500),
 created_at DATETIME(3) NOT NULL, resolved_at DATETIME(3),
 KEY idx_review_order(order_line_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE cps_attribution_settlement_outbox (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, order_line_id BIGINT NOT NULL,
 attribution_id BIGINT NOT NULL, event_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 last_error VARCHAR(128), created_at DATETIME(3) NOT NULL, completed_at DATETIME(3),
 UNIQUE KEY uk_attribution_settlement(attribution_id), UNIQUE KEY uk_attribution_event(event_key),
 KEY idx_attribution_pending(status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
 SELECT '追踪PID租约小时','cps.attribution.lease.hours','48','N','line9',NOW(),'方案明确的默认值，可后台调整'
 WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.attribution.lease.hours');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
 SELECT '无主订单认领保留天数','cps.attribution.unclaimed.days','30','N','line9',NOW(),'到期归平台，历史记录保留'
 WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.attribution.unclaimed.days');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
 SELECT '订单手动认领时限小时','cps.attribution.claim.hours','','N','line9',NOW(),'待后台配置；未配置时不开放认领，不擅定业务数字'
 WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.attribution.claim.hours');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
 SELECT '商品匹配候选窗口小时','cps.attribution.item.match.hours','1','N','line9',NOW(),'方案默认1小时，最大24小时；目前只记候选，待共享契约补齐后自动匹配'
 WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.attribution.item.match.hours');
