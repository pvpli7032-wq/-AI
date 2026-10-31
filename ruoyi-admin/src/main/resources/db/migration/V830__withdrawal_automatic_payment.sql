-- Automatic payment is opt-in. Existing approvals cannot become automatic candidates.
ALTER TABLE cps_withdrawal
    ADD COLUMN auto_pay_eligible BOOLEAN NOT NULL DEFAULT FALSE,
    ADD KEY idx_withdraw_auto_queue(status,auto_pay_eligible,id);

UPDATE cps_withdraw_policy
SET config=JSON_INSERT(config,'$.autoPayEnabled',CAST('false' AS JSON),'$.autoPayBelowAmount','0.00')
WHERE id=1;

-- A technical recovery tick; every candidate re-reads the live automatic-payment settings,
-- risk controls and real-time exposure. Submitted/unknown payments are queried separately.
INSERT INTO sys_job(job_name,job_group,invoke_target,cron_expression,misfire_policy,concurrent,status,create_by,create_time,remark)
SELECT '线12 自动打款恢复','SYSTEM','cpsAutomaticWithdrawalTask.process()','0 * * * * ?','3','1','0','line12-migration',NOW(),
 '每分钟恢复已提交且明确自动审核通过的申请；默认开关关闭、额度0；提交前复核，未决结果只查询原提现号。'
WHERE NOT EXISTS(SELECT 1 FROM sys_job WHERE invoke_target='cpsAutomaticWithdrawalTask.process()');
