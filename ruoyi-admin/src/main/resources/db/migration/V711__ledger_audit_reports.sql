-- Audit metadata only. No account balance, historical ledger flow, or business threshold is changed.
CREATE TABLE cps_ledger_audit_lock (
 lock_id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO cps_ledger_audit_lock(lock_id) VALUES (1);

CREATE TABLE cps_ledger_audit_run (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 audit_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 checked_accounts BIGINT NOT NULL DEFAULT 0,
 mismatched_accounts BIGINT NOT NULL DEFAULT 0,
 started_at DATETIME(3) NOT NULL,
 completed_at DATETIME(3),
 UNIQUE KEY uk_ledger_audit_key(audit_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cps_ledger_audit_difference (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 run_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 expected_available DECIMAL(14,2) NOT NULL,
 expected_frozen DECIMAL(14,2) NOT NULL,
 expected_withdrawing DECIMAL(14,2) NOT NULL,
 expected_paid DECIMAL(14,2) NOT NULL,
 recorded_available DECIMAL(14,2) NOT NULL,
 recorded_frozen DECIMAL(14,2) NOT NULL,
 recorded_withdrawing DECIMAL(14,2) NOT NULL,
 recorded_paid DECIMAL(14,2) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_ledger_audit_difference(run_id,user_id),
 KEY idx_ledger_audit_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The existing V706 cpsSettlementUnfreezeTask.run() hourly schedule now also
-- invokes LedgerAuditMonitor through SettlementMaintenanceJob. Reuse that allowed
-- task entry; preserve all administrator changes to cron, status and concurrency.
