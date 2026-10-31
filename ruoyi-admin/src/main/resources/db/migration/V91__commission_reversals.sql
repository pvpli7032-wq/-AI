ALTER TABLE sys_commission_flow
    ADD COLUMN original_flow_id BIGINT NULL COMMENT '冲正对应原流水',
    ADD COLUMN idempotency_key VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL COMMENT '冲正请求幂等键',
    ADD COLUMN operator_id BIGINT NULL COMMENT '冲正操作人',
    ADD COLUMN reversal_reason VARCHAR(500) NULL COMMENT '冲正原因',
    ADD UNIQUE KEY uk_flow_reversal_original (original_flow_id),
    ADD UNIQUE KEY uk_flow_reversal_key (idempotency_key);

INSERT INTO sys_menu (menu_name, parent_id, order_num, path, menu_type, visible, status, perms, icon, create_by, create_time, remark)
SELECT '佣金流水冲正', menu_id, 3, '', 'F', '0', '0', 'system:commission:flow:reversal', '#', 'system', NOW(), '允许整笔冲正佣金流水'
FROM sys_menu WHERE perms = 'system:commission:flow:list' AND menu_type = 'C'
  AND NOT EXISTS (SELECT 1 FROM (SELECT perms FROM sys_menu) existing WHERE existing.perms = 'system:commission:flow:reversal');
