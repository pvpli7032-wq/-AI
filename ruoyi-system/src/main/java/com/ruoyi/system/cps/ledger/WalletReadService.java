package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** SELECT-only wallet projection; deliberately independent of account creation/posting services. */
@Service
public class WalletReadService {
    private final JdbcTemplate jdbc;
    public WalletReadService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Page(List<WalletFlow> rows, long total, int page, int pageSize) {}

    @Transactional(readOnly = true)
    public WalletAccount account(long userId) {
        requireUser(userId);
        List<WalletAccount> rows = jdbc.query("""
            SELECT balance,frozen_amount,withdrawing_amount,withdrawn
            FROM sys_agent_commission_account WHERE user_id=?
            """, (r, n) -> WalletAccount.of(true, r.getBigDecimal("balance"),
                r.getBigDecimal("frozen_amount"), r.getBigDecimal("withdrawing_amount"),
                r.getBigDecimal("withdrawn")), userId);
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return rows.isEmpty() ? WalletAccount.of(false, zero, zero, zero, zero) : rows.getFirst();
    }

    /** Date range is [from,to). No client-supplied identity or ordering enters the SQL. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page flows(long userId, String flowType, LocalDateTime from, LocalDateTime to, int page, int pageSize) {
        requireUser(userId);
        if (page < 1 || pageSize < 1 || pageSize > 100
                || (from != null && to != null && !from.isBefore(to)))
            throw new ServiceException("Invalid wallet page or date range");
        if (flowType != null && (flowType.isBlank() || flowType.length() > 32))
            throw new ServiceException("Invalid wallet flow type");
        StringBuilder where = new StringBuilder(" WHERE f.user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (flowType != null) { where.append(" AND f.flow_type=?"); args.add(flowType); }
        if (from != null) { where.append(" AND f.create_time>=?"); args.add(from); }
        if (to != null) { where.append(" AND f.create_time<?"); args.add(to); }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow f" + where,
            Long.class, args.toArray());
        args.add(pageSize);
        args.add(((long) page - 1) * pageSize);
        List<WalletFlow> rows = jdbc.query("""
            SELECT f.id,f.flow_type,f.create_time,f.amount,f.debit_account,f.credit_account,
              o.flow_type original_type FROM sys_commission_flow f
            LEFT JOIN sys_commission_flow o ON o.id=f.original_flow_id AND o.user_id=f.user_id
            """ + where + " ORDER BY f.create_time DESC,f.id DESC LIMIT ? OFFSET ?", (r, n) -> {
                var time = r.getTimestamp("create_time");
                return WalletFlow.of(r.getLong("id"), r.getString("flow_type"),
                    time == null ? null : time.toLocalDateTime(), r.getBigDecimal("amount"),
                    r.getString("debit_account"), r.getString("credit_account"), r.getString("original_type"));
            }, args.toArray());
        return new Page(List.copyOf(rows), total == null ? 0 : total, page, pageSize);
    }
    private static void requireUser(long userId) {
        if (userId <= 0) throw new ServiceException("Authenticated wallet user required");
    }
}
