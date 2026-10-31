-- V2 has already run in a local acceptance database; preserve its checksum.
-- Widen amounts without inventing precision lost before this migration.
-- Widen the old CHAR(1) status BEFORE writing the current string values.
ALTER TABLE `sys_order_commission`
    MODIFY COLUMN `estimated_commission` DECIMAL(14,4) DEFAULT NULL COMMENT '预估佣金',
    MODIFY COLUMN `actual_commission` DECIMAL(14,4) DEFAULT NULL COMMENT '实际佣金',
    MODIFY COLUMN `settle_status` VARCHAR(20) DEFAULT NULL
        COMMENT '结算状态(unsettled:未结算 settled:已结算)';

-- V2 documented legacy 0/1. Preserve any other explicit value for review.
UPDATE `sys_order_commission`
SET `settle_status` = CASE WHEN `settle_status` = '1' THEN 'settled' ELSE 'unsettled' END
WHERE `settle_status` IS NULL OR `settle_status` IN ('0', '1');

ALTER TABLE `sys_order_commission`
    MODIFY COLUMN `settle_status` VARCHAR(20) NOT NULL DEFAULT 'unsettled'
        COMMENT '结算状态(unsettled:未结算 settled:已结算)';
