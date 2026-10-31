package com.ruoyi.system.cps.ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recomputes all four user pockets from immutable flows. Audit reports and operational
 * alerts are durable; this service never repairs an account or rewrites a ledger flow.
 */
@Service
public class LedgerAuditMonitor {
    public static final String MISMATCH = "LEDGER_BALANCE_MISMATCH";
    public static final String FAILED = "LEDGER_AUDIT_FAILED";
    private static final int PAGE_SIZE = 250;
    private final JdbcTemplate jdbc;
    private final LedgerAuditService auditor;
    private final TransactionTemplate transactions;

    public LedgerAuditMonitor(JdbcTemplate jdbc, LedgerAuditService auditor,
                              PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.auditor = auditor;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public record Report(long runId, long checkedAccounts, long mismatchedAccounts) {}

    /** The existing hourly Quartz settlement entry calls this method. */
    public Report runHourly() {
        return audit("hourly:" + Instant.now().truncatedTo(ChronoUnit.HOURS));
    }

    public Report audit(String auditKey) {
        if (auditKey == null || !auditKey.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new IllegalArgumentException("invalid audit key");
        try {
            return transactions.execute(status -> scan(auditKey));
        } catch (RuntimeException failure) {
            // The failed scan rolls back first. Report its failure in a separate transaction.
            // If the database itself is unavailable, propagate both failures to Quartz.
            try {
                transactions.executeWithoutResult(status ->
                    alert(FAILED, "ledger", "账本审计执行失败，未生成成功报告；请检查任务日志并重试"));
            } catch (RuntimeException alertFailure) {
                failure.addSuppressed(alertFailure);
            }
            throw failure;
        }
    }

    private Report scan(String auditKey) {
        // One scanner across application instances. No account row is locked or updated.
        jdbc.queryForObject("SELECT lock_id FROM cps_ledger_audit_lock WHERE lock_id=1 FOR UPDATE", Long.class);
        List<Report> prior = jdbc.query(
            "SELECT id,checked_accounts,mismatched_accounts FROM cps_ledger_audit_run WHERE audit_key=?",
            (r, n) -> new Report(r.getLong("id"), r.getLong("checked_accounts"), r.getLong("mismatched_accounts")),
            auditKey);
        if (!prior.isEmpty()) return prior.getFirst();

        jdbc.update("INSERT INTO cps_ledger_audit_run(audit_key,started_at) VALUES (?,CURRENT_TIMESTAMP(3))", auditKey);
        Long runId = jdbc.queryForObject("SELECT id FROM cps_ledger_audit_run WHERE audit_key=?", Long.class, auditKey);
        long after = 0, checked = 0, mismatched = 0;
        while (true) {
            // All pages use the same repeatable-read snapshot: account and flow totals
            // cannot come from different moments while normal postings continue.
            List<LedgerAuditService.BalanceCheck> page = auditor.checkAfter(after, PAGE_SIZE);
            if (page.isEmpty()) break;
            for (LedgerAuditService.BalanceCheck check : page) {
                if (check.userId() <= after) throw new IllegalStateException("audit page did not advance");
                after = check.userId();
                checked++;
                String subject = "account:" + check.userId();
                if (check.matches()) {
                    clear(MISMATCH, subject);
                    continue;
                }
                mismatched++;
                jdbc.update("""
                    INSERT INTO cps_ledger_audit_difference(
                      run_id,user_id,expected_available,expected_frozen,expected_withdrawing,expected_paid,
                      recorded_available,recorded_frozen,recorded_withdrawing,recorded_paid,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))
                    """, runId, check.userId(), check.available(), check.frozen(), check.withdrawing(), check.paid(),
                    check.recordedAvailable(), check.recordedFrozen(), check.recordedWithdrawing(), check.recordedPaid());
                alert(MISMATCH, subject, "账户余额与不可变流水重算不一致；审计批次=" + runId
                    + "；保留原账户和流水，须人工核查并按合法冲正流程处理");
            }
            if (page.size() < PAGE_SIZE) break;
        }
        jdbc.update("UPDATE cps_ledger_audit_run SET checked_accounts=?,mismatched_accounts=?,"
            + "completed_at=CURRENT_TIMESTAMP(3) WHERE id=?", checked, mismatched, runId);
        clear(FAILED, "ledger");
        return new Report(runId, checked, mismatched);
    }

    /** Existing health endpoints expose these alerts; no external delivery is claimed. */
    private void alert(String kind, String subject, String message) {
        jdbc.update("INSERT INTO cps_health_alert_state(kind,subject,condition_active) VALUES (?,?,0)"
            + " ON DUPLICATE KEY UPDATE kind=VALUES(kind)", kind, subject);
        Integer active = jdbc.queryForObject(
            "SELECT condition_active FROM cps_health_alert_state WHERE kind=? AND subject=? FOR UPDATE",
            Integer.class, kind, subject);
        if (active != null && active != 0) return;
        jdbc.update("INSERT INTO cps_health_alert(kind,subject,message,created_at) VALUES (?,?,?,CURRENT_TIMESTAMP(3))",
            kind, subject, message);
        jdbc.update("UPDATE cps_health_alert_state SET condition_active=1,last_emitted_at=CURRENT_TIMESTAMP(3)"
            + " WHERE kind=? AND subject=?", kind, subject);
    }

    private void clear(String kind, String subject) {
        jdbc.update("UPDATE cps_health_alert_state SET condition_active=0"
            + " WHERE kind=? AND subject=? AND condition_active=1", kind, subject);
    }
}
