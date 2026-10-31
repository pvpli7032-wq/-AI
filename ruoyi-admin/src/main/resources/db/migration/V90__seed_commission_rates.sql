-- 零比例为占位值，须由运营在上线前配置；不覆盖已有配置。
INSERT INTO sys_commission_config (config_key, config_value, remark)
VALUES ('self_rate', 0.00, '占位配置，上线前设置自购比例'),
       ('promotion_rate', 0.00, '占位配置，上线前设置推广比例')
ON DUPLICATE KEY UPDATE config_key = config_key;
