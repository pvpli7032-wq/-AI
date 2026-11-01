package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-only reconciliation; discrepancies are never repaired by editing balances or flows. */
@Service
public class LedgerAuditService {
    private final JdbcTemplate jdbc;
    public LedgerAuditService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record BalanceCheck(long userId, BigDecimal available, BigDecimal frozen, BigDecimal withdrawing,
        BigDecimal paid, BigDecimal recordedAvailable, BigDecimal recordedFrozen,
        BigDecimal recordedWithdrawing, BigDecimal recordedPaid) {
        public boolean matches() {
            return available.compareTo(recordedAvailable) == 0 && frozen.compareTo(recordedFrozen) == 0
                && withdrawing.compareTo(recordedWithdrawing) == 0 && paid.compareTo(recordedPaid) == 0;
        }
    }
    /** Bounded page so a scheduled caller can scan all accounts without retaining a global lock. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<BalanceCheck> checkAfter(long afterUserId, int limit) {
        if (afterUserId < 0 || limit < 1 || limit > 500) throw new IllegalArgumentException("invalid audit page");
        return jdbc.query("""
            SELECT a.user_id,a.balance,a.frozen_amount,a.withdrawing_amount,a.withdrawn,
              COALESCE(f.available,0) available,COALESCE(f.frozen,0) frozen,
              COALESCE(f.withdrawing,0) withdrawing,COALESCE(f.paid,0) paid
            FROM sys_agent_commission_account a LEFT JOIN (
              SELECT user_id,
                SUM(CASE WHEN debit_account IS NULL AND credit_account IS NULL THEN amount
                    WHEN credit_account='USER_AVAILABLE' THEN amount
                    WHEN debit_account='USER_AVAILABLE' THEN -amount ELSE 0 END) available,
                SUM(CASE WHEN credit_account='USER_FROZEN' THEN amount
                    WHEN debit_account='USER_FROZEN' THEN -amount ELSE 0 END) frozen,
                SUM(CASE WHEN credit_account='USER_WITHDRAWING' THEN amount
                    WHEN debit_account='USER_WITHDRAWING' THEN -amount ELSE 0 END) withdrawing,
                SUM(CASE WHEN credit_account='PAYOUT' THEN amount
                    WHEN debit_account='PAYOUT' THEN -amount
                    WHEN debit_account IS NULL AND flow_type='withdraw' THEN -amount
                    WHEN debit_account IS NULL AND flow_type='reversal' AND original_flow_id IN
                      (SELECT id FROM sys_commission_flow WHERE flow_type='withdraw') THEN -amount ELSE 0 END) paid
              FROM sys_commission_flow GROUP BY user_id
            ) f ON f.user_id=a.user_id WHERE a.user_id>? ORDER BY a.user_id LIMIT ?
            """, (r, n) -> new BalanceCheck(r.getLong("user_id"), r.getBigDecimal("available"),
                r.getBigDecimal("frozen"), r.getBigDecimal("withdrawing"), r.getBigDecimal("paid"),
                r.getBigDecimal("balance"), r.getBigDecimal("frozen_amount"),
                r.getBigDecimal("withdrawing_amount"), r.getBigDecimal("withdrawn")), afterUserId, limit);
    }
}
