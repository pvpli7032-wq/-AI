-- Textual user notices only; these settings do not alter commission or reward eligibility.
ALTER TABLE cps_settlement_notification DROP CHECK ck_cps_notification_reason;
ALTER TABLE cps_settlement_notification ADD CONSTRAINT ck_cps_notification_reason CHECK
 (reason IN ('BINDING_FAILURE','REBATE_INVALID','REFUND','WHOLE_MONTH_PENALTY','PDD_PRICE_COMPARE_ZERO','PDD_ZERO_COMMISSION'));
CREATE TABLE cps_settlement_pdd_notice (
 id BIGINT NOT NULL AUTO_INCREMENT,
 order_line_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 reason VARCHAR(64) NOT NULL,
 event_key VARCHAR(128) NOT NULL,
 business_key_hash CHAR(64) NOT NULL,
 message VARCHAR(1000) NOT NULL,
 source_hash CHAR(64) NOT NULL,
 source_evidence LONGTEXT NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 PRIMARY KEY(id), UNIQUE KEY uk_pdd_notice_order_reason(order_line_id,reason),
 UNIQUE KEY uk_pdd_notice_outbox(business_key_hash),
 KEY idx_pdd_notice_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sys_config(config_name,config_key,config_value,config_type)
SELECT '拼多多比价零佣金提示开关','cps.settlement.pdd.priceCompareNotice.enabled','true','N'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.settlement.pdd.priceCompareNotice.enabled');
INSERT INTO sys_config(config_name,config_key,config_value,config_type)
SELECT '拼多多比价零佣金提示文案','cps.settlement.pdd.priceCompareNotice.message',
'联盟将本订单标记为比价订单，实际佣金为0，因此不计入有效奖励订单。','N'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.settlement.pdd.priceCompareNotice.message');
INSERT INTO sys_config(config_name,config_key,config_value,config_type)
SELECT '拼多多零佣金通用提示开关','cps.settlement.pdd.zeroCommissionNotice.enabled','true','N'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.settlement.pdd.zeroCommissionNotice.enabled');
INSERT INTO sys_config(config_name,config_key,config_value,config_type)
SELECT '拼多多零佣金通用提示文案','cps.settlement.pdd.zeroCommissionNotice.message',
'本订单联盟实际佣金为0，不计入有效奖励订单。如需了解具体原因，请联系客服核对。','N'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.settlement.pdd.zeroCommissionNotice.message');
