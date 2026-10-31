-- No fabricated provider event time: automated penalty records are evidence, not order events.
ALTER TABLE cps_punish_import
    ADD COLUMN automatic_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN raw_id BIGINT NULL,
    ADD COLUMN payload_text LONGTEXT NULL,
    ADD COLUMN punish_status VARCHAR(16) NULL,
    ADD COLUMN violation_type VARCHAR(255) NULL,
    ADD COLUMN tk_adzone_id VARCHAR(128) NULL,
    ADD COLUMN tk_site_id VARCHAR(128) NULL,
    ADD UNIQUE KEY uk_automatic_penalty(automatic_key);

INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '淘宝已收货视为结算','cps.order.tbk.received-as-settled','false','Y','system',NOW(),
 '默认关闭；启用也不绕过线8合法路径，付款直达结算仍记异常，时间缺失仍为空'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.order.tbk.received-as-settled');

-- Schedules use the deployment JVM timezone. Disabled until credentials, ownership, window policy
-- and real attribution/settlement implementations are configured. Enable in Quartz administration.
-- Incremental cadence is editable for sale-day throttling; checkpoints catch up without skipping.
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线10 订单增量','SYSTEM','cpsOrderSyncTask.incremental()','0 */5 * * * ?','2','1','1','line10-migration',NOW(),
 '每5分钟，窗口重叠及失败补偿；默认暂停，完成订单采集策略及真实下游配置后启用'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsOrderSyncTask.incremental()');
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线10 每日订单回补','SYSTEM','cpsOrderSyncTask.backfill()','0 30 2 * * ?','2','1','1','line10-migration',NOW(),
 '每日02:30，回补周期由策略显式配置；默认暂停'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsOrderSyncTask.backfill()');
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线10 维权处罚','SYSTEM','cpsOrderSyncTask.afterSale()','0 0 * * * ?','2','1','1','line10-migration',NOW(),
 '每小时分别尝试维权与处罚；处罚按创建时间回扫显式lookbackSeconds，单窗口不超过30天；默认暂停'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsOrderSyncTask.afterSale()');
