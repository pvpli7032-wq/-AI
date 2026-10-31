-- Line 12. No user data or real business thresholds are seeded.
CREATE TABLE cps_risk_user_state (
 user_id BIGINT NOT NULL PRIMARY KEY,
 withdrawal_blocked BOOLEAN NOT NULL DEFAULT FALSE,
 rewards_paused BOOLEAN NOT NULL DEFAULT FALSE,
 manual_review BOOLEAN NOT NULL DEFAULT FALSE,
 updated_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_risk_device_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 user_id BIGINT NOT NULL, device_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 observed_at DATETIME(3) NOT NULL, operator_id BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_risk_device_event(event_key), KEY idx_risk_device(device_hash,observed_at,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_risk_signal (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 evidence_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id BIGINT NOT NULL, signal_type VARCHAR(64) NOT NULL,
 evidence_ref CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_risk_signal(evidence_key), KEY idx_risk_signal_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_risk_review (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,
 withdrawal_blocked BOOLEAN NOT NULL, rewards_paused BOOLEAN NOT NULL, manual_review BOOLEAN NOT NULL,
 reason VARCHAR(500) NOT NULL, operator_id BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_risk_review_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_health_observation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(32) NOT NULL, subject VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 observed_at DATETIME(3) NOT NULL, authorized BOOLEAN, balance DECIMAL(14,2),
 current_orders BIGINT, current_refunds BIGINT, baseline_orders BIGINT, baseline_refunds BIGINT,
 operator_id BIGINT NOT NULL, created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_health_event(event_key), KEY idx_health_observation(kind,subject,observed_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_health_alert_state (
 kind VARCHAR(64) NOT NULL, subject VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 condition_active BOOLEAN NOT NULL DEFAULT FALSE, last_emitted_at DATETIME(3),
 PRIMARY KEY(kind,subject)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_health_alert (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 kind VARCHAR(64) NOT NULL, subject VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 message VARCHAR(500) NOT NULL, created_at DATETIME(3) NOT NULL,
 KEY idx_health_alert(kind,subject,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Explicitly disabled placeholders; configure thresholds before enabling abuse detectors.
-- Authorization-expiry alerts intentionally have no enable/disable parameter.
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：同设备新用户数','cps.risk.device.newUsers','0','N','migration',NOW(),'0关闭；上线前由后台设置'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.risk.device.newUsers');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：同设备观察分钟','cps.risk.device.windowMinutes','0','N','migration',NOW(),'0关闭；上线前由后台设置'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.risk.device.windowMinutes');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：受邀同商品人数','cps.risk.invite.sameItemBuyers','0','N','migration',NOW(),'0关闭；按下单邀请关系快照'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.risk.invite.sameItemBuyers');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：低价商品上限','cps.risk.invite.lowPrice','0','N','migration',NOW(),'0关闭；上线前由后台设置'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.risk.invite.lowPrice');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：订单观察天数','cps.risk.orders.windowDays','0','N','migration',NOW(),'0关闭；上线前由后台设置'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.risk.orders.windowDays');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：全部退款最少单数','cps.risk.refund.minimumOrders','0','N','migration',NOW(),'0关闭；上线前由后台设置'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.risk.refund.minimumOrders');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：通道最低预付余额','cps.health.channel.minimumBalance','0','N','migration',NOW(),'0关闭余额告警，不影响授权告警'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.health.channel.minimumBalance');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：退款率告警倍数','cps.health.refund.rateMultiplier','0','N','migration',NOW(),'0关闭退款告警，不影响授权告警'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.health.refund.rateMultiplier');
INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '示例占位：退款率最少样本数','cps.health.refund.minimumOrders','0','N','migration',NOW(),'0关闭退款告警，不影响授权告警'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.health.refund.minimumOrders');
