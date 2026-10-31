-- Quartz uses the deployment JVM timezone for these cron expressions.
-- Evidence and completed_at are stored in UTC; recent() reconciles UTC month cohorts.
-- Technical polling intervals are editable in the existing scheduled-task administration.
-- All three jobs are non-concurrent. None can submit a payment.
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线12 每日四边对账','SYSTEM','cpsReconTask.recent()','0 10 2 * * ?','2','1','0','line12-migration',NOW(),
 '每天部署时区02:10核对当前及前一UTC月份受影响订单；累计证据匹配晚到账和跨月更正。历史月份可手工补跑。'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsReconTask.recent()');

INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线12 每日敞口快照','SYSTEM','cpsExposureTask.snapshot()','0 20 2 * * ?','2','1','0','line12-migration',NOW(),
 '每天部署时区02:20记录已观测敞口峰值；提现时另行实时校验；包含未分摊用户债务的保守占用。'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsExposureTask.snapshot()');

INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线12 提现结果查询','SYSTEM','cpsWithdrawalHealthTask.queryPending()','0 */5 * * * ?','3','1','0','line12-migration',NOW(),
 '每5分钟只查询原提现号的未决结果；不提交打款、不生成新提现号。'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsWithdrawalHealthTask.queryPending()');
