-- Keep existing financial values. Zero explicitly disables the ceiling; blank remains fail-closed.
UPDATE sys_config SET remark='空值/无效值禁止提现；0关闭敞口上限；正数为人民币上限，达到或超过时执行cps.exposure.action。'
WHERE config_key='cps.exposure.limit';

INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '敞口到限动作','cps.exposure.action','MANUAL_REVIEW','N','line12-migration',UTC_TIMESTAMP(),
 'MANUAL_REVIEW转人工审核（默认）；REJECT禁止提现。达到或超过上限都执行所选动作。'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.exposure.action');

INSERT INTO sys_config(config_name,config_key,config_value,config_type,create_by,create_time,remark)
SELECT '敞口定时计算间隔（分钟）','cps.exposure.intervalMinutes','60','N','line12-migration',UTC_TIMESTAMP(),
 '正整数分钟，默认每小时计算；修改后下一个分钟轮询读取生效。提现时始终另行实时计算。'
WHERE NOT EXISTS(SELECT 1 FROM sys_config WHERE config_key='cps.exposure.intervalMinutes');

CREATE TABLE cps_exposure_schedule (
 id INT NOT NULL PRIMARY KEY,
 last_capture_at DATETIME(3) NULL COMMENT 'UTC，首次轮询立即采样；成功采样与游标同事务提交',
 CONSTRAINT chk_exposure_schedule_singleton CHECK(id=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO cps_exposure_schedule(id,last_capture_at) VALUES(1,NULL);

CREATE TABLE cps_exposure_policy_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 operator_id BIGINT NOT NULL,
 before_config JSON NOT NULL,
 after_config JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 KEY idx_exposure_policy_audit_time(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Quartz only polls. The database checkpoint and live setting determine when a full scan is due.
-- This preserves the existing task's enabled/paused state and invoke target.
UPDATE sys_job SET job_name='线12 敞口计算轮询',cron_expression='0 * * * * ?',concurrent='1',misfire_policy='2',
 update_by='line12-migration',update_time=UTC_TIMESTAMP(),
 remark='每分钟检查后台配置的敞口计算间隔（默认60分钟）；数据库行锁防多实例重复采样，提现实时校验始终保留。'
WHERE invoke_target='cpsExposureTask.snapshot()';
