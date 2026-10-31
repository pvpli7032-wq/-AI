-- 只停用 V90 的占位配置，不覆盖运营修改过备注的配置。
UPDATE sys_commission_config SET status = '1'
WHERE config_value = 0
  AND ((config_key = 'self_rate' AND BINARY remark = BINARY '占位配置，上线前设置自购比例')
    OR (config_key = 'promotion_rate' AND BINARY remark = BINARY '占位配置，上线前设置推广比例'));
