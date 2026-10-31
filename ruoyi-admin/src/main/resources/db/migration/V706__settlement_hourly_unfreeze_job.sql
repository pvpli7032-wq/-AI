-- Technical hourly schedule; business freeze duration is exclusively supplied by the policy adapter.
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT 'CPS hourly settlement unfreeze','SYSTEM','cpsSettlementUnfreezeTask.run()','0 0 * * * ?','2','1','0','line11-migration',NOW(),
 'Releases at most 1000 due lines per hour; skips disputed items; bans restrict withdrawals, never erase balances. Requires merged settlement policy/notification adapters.'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsSettlementUnfreezeTask.run()');
