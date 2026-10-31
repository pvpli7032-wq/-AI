-- Operators must configure limits before the public claim endpoint is enabled.
INSERT INTO sys_config(config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '每用户每分钟订单认领次数', 'cps.attribution.claim.requests-per-minute', '', 'N', 'line9', NOW(), '后台配置正整数；未配置时关闭认领'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='cps.attribution.claim.requests-per-minute');
INSERT INTO sys_config(config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '每用户待核对订单认领上限', 'cps.attribution.claim.max-pending', '', 'N', 'line9', NOW(), '后台配置正整数；未配置时关闭认领'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='cps.attribution.claim.max-pending');
