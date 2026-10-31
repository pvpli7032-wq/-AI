-- Independent cleanup jobs leave operator-edited schedules untouched on repeat deployment.
INSERT INTO sys_job
 (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线9过期归因令牌清理','DEFAULT','cpsAttributionTask.cleanupTokens(200)',
 '0 15 3 * * ?','3','1','0','line9',NOW(),
 '分批清理已过保留期的归因令牌；保留期使用无主订单配置，批次可在后台调整。'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE (job_group='DEFAULT' AND job_name='线9过期归因令牌清理')
 OR invoke_target LIKE 'cpsAttributionTask.cleanupTokens(%');

INSERT INTO sys_job
 (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线9过期转链保留记录清理','DEFAULT','cpsAttributionTask.cleanupReservations(200)',
 '0 30 3 * * ?','3','1','0','line9',NOW(),
 '分批清理已过期租约的中断转链记录，不影响有效租约；批次可在后台调整。'
WHERE NOT EXISTS (SELECT 1 FROM sys_job WHERE (job_group='DEFAULT' AND job_name='线9过期转链保留记录清理')
 OR invoke_target LIKE 'cpsAttributionTask.cleanupReservations(%');
