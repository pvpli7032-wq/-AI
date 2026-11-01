INSERT INTO sys_config(config_name, config_key, config_value, config_type, create_by, create_time, remark)
SELECT '归因补结算最大尝试次数', 'cps.attribution.settlement.max-attempts', '10', 'N', 'line9', NOW(), '技术重试上限，可在后台调整；超过后人工处理'
WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='cps.attribution.settlement.max-attempts');
