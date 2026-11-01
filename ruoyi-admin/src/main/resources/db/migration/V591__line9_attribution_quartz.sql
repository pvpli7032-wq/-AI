-- Two independent jobs: an expiry failure cannot prevent settlement retries.
-- New jobs are enabled; cadence, batch size and pause state remain editable in the existing job UI.
-- Preserve an existing job matched by its fixed name/group or its operation, including operator changes.
INSERT INTO sys_job
 (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT
 '线9无主订单到期','DEFAULT','cpsAttributionTask.expireUnclaimed(200)',
 '0 * * * * ?','3','1','0','line9',NOW(),
 '按批处理到期无主订单；可在定时任务后台修改cron和批次（1至500）。错过的触发不追跑，下一轮继续处理持久数据。'
WHERE NOT EXISTS (
 SELECT 1 FROM sys_job
 WHERE (job_group='DEFAULT' AND job_name='线9无主订单到期')
    OR invoke_target LIKE 'cpsAttributionTask.expireUnclaimed(%'
);

INSERT INTO sys_job
 (job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT
 '线9归因补结算重试','DEFAULT','cpsAttributionTask.retrySettlement(200)',
 '30 * * * * ?','3','1','0','line9',NOW(),
 '重试持久归因补结算队列；可修改cron和批次（1至500）。已发送、已替代或待人工复核的任务不会被重复正常投递。'
WHERE NOT EXISTS (
 SELECT 1 FROM sys_job
 WHERE (job_group='DEFAULT' AND job_name='线9归因补结算重试')
    OR invoke_target LIKE 'cpsAttributionTask.retrySettlement(%'
);
