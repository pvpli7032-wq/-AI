-- Add fields referenced by the current entities and MyBatis mappings.
-- Preserve the V1 columns and their data. NULL means the value is unknown;
-- this migration does not infer attribution, settlement, token, or identity data.

ALTER TABLE `sys_order_commission`
    ADD COLUMN `buyer_user_id` BIGINT DEFAULT NULL COMMENT '买家用户ID',
    ADD COLUMN `estimated_commission` DECIMAL(10,2) DEFAULT NULL COMMENT '预估佣金',
    ADD COLUMN `commission_rate` DECIMAL(5,2) DEFAULT NULL COMMENT '佣金比例(%)',
    ADD COLUMN `actual_commission` DECIMAL(10,2) DEFAULT NULL COMMENT '实际佣金',
    ADD COLUMN `order_time` DATETIME DEFAULT NULL COMMENT '下单时间',
    ADD COLUMN `settle_status` CHAR(1) DEFAULT NULL COMMENT '结算状态(0:未结算 1:已结算)';

ALTER TABLE `sys_commission_flow`
    ADD COLUMN `related_order_id` VARCHAR(100) DEFAULT NULL COMMENT '关联订单ID',
    ADD COLUMN `description` VARCHAR(500) DEFAULT NULL COMMENT '流水描述';

ALTER TABLE `sys_commission_config`
    ADD COLUMN `description` VARCHAR(500) DEFAULT NULL COMMENT '配置描述';

-- The legacy id/openid/token/expire_time columns remain intact. These new
-- columns satisfy the current mapper's names, but do not synchronize the two
-- session formats. See sql/schema-differences.md for the remaining mapper and
-- entity incompatibilities; authentication changes belong to later tasks.
ALTER TABLE `sys_miniapp_session`
    ADD COLUMN `session_id` BIGINT DEFAULT NULL COMMENT '当前会话Mapper使用的会话ID',
    ADD COLUMN `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
    ADD COLUMN `open_id` VARCHAR(100) DEFAULT NULL COMMENT '当前会话Mapper使用的微信OpenID',
    ADD COLUMN `access_token` VARCHAR(500) DEFAULT NULL COMMENT 'JWT访问Token',
    ADD COLUMN `refresh_token` VARCHAR(500) DEFAULT NULL COMMENT '刷新Token',
    ADD COLUMN `expires_at` DATETIME DEFAULT NULL COMMENT '访问Token过期时间',
    ADD COLUMN `refresh_expires_at` DATETIME DEFAULT NULL COMMENT '刷新Token过期时间',
    ADD COLUMN `update_time` DATETIME DEFAULT NULL COMMENT '更新时间';

ALTER TABLE `sys_miniapp_login_log`
    ADD COLUMN `log_id` BIGINT DEFAULT NULL COMMENT '当前登录日志Mapper使用的日志ID',
    ADD COLUMN `open_id` VARCHAR(100) DEFAULT NULL COMMENT '当前登录日志Mapper使用的微信OpenID';
