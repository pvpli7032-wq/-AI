package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import com.ruoyi.common.exception.ServiceException;

/** Unit SQL/argument contracts only; not a substitute for real MySQL execution. */
public class WalletReadTest {
    private final RecordingJdbc jdbc = new RecordingJdbc();
    private final WalletReadService service = new WalletReadService(jdbc);

    @Test public void absentAccountIsZeroAndNeverCreated() {
        WalletAccount account = service.account(41);
        assertFalse(account.accountExists());
        assertMoney("0", account.available()); assertMoney("0", account.frozen());
        assertMoney("0", account.withdrawing()); assertMoney("0", account.paid());
        assertMoney("0", account.debt());
        assertEquals(1, jdbc.sql.size());
        assertTrue(jdbc.sql.getFirst().contains("WHERE user_id=?"));
        assertEquals(List.of(41L), jdbc.arguments.getFirst());
        // No DataSource is configured: any JdbcTemplate insert/update would fail this test.
    }

    @Test public void storedBucketsPreserveDebtInsteadOfReconstructingOrClamping() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getBigDecimal("balance")).thenReturn(new BigDecimal("-8.25"));
        when(row.getBigDecimal("frozen_amount")).thenReturn(new BigDecimal("3.00"));
        when(row.getBigDecimal("withdrawing_amount")).thenReturn(new BigDecimal("4.00"));
        when(row.getBigDecimal("withdrawn")).thenReturn(new BigDecimal("9.00"));
        jdbc.row = row;
        WalletAccount a = service.account(42);
        assertTrue(a.accountExists()); assertMoney("-8.25", a.available());
        assertMoney("8.25", a.debt()); assertMoney("3", a.frozen());
        assertMoney("4", a.withdrawing()); assertMoney("9", a.paid());
        assertEquals(1, jdbc.sql.size());
    }

    @Test public void filtersAreBoundAndStablePaginationUsesLongOffset() throws Exception {
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0), to = from.plusDays(1);
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(12L);
        when(row.getString("flow_type")).thenReturn("refund_self");
        when(row.getTimestamp("create_time")).thenReturn(Timestamp.valueOf(from));
        when(row.getBigDecimal("amount")).thenReturn(new BigDecimal("-2.00"));
        jdbc.row = row;
        var result = service.flows(42, "refund_self", from, to, Integer.MAX_VALUE, 100);
        assertEquals(7, result.total()); assertEquals(1, result.rows().size());
        assertMoney("-2", result.rows().getFirst().userDelta());
        assertEquals(12, result.rows().getFirst().id());
        assertEquals(from, result.rows().getFirst().createTime());
        for (String sql : jdbc.sql) {
            assertTrue(sql.contains("f.user_id=? AND f.flow_type=? AND f.create_time>=? AND f.create_time<?"));
            assertFalse(sql.contains("refund_self"));
        }
        assertEquals(List.of(42L, "refund_self", from, to), jdbc.arguments.getFirst());
        assertEquals(List.of(42L, "refund_self", from, to, 100, 214748364600L), jdbc.arguments.get(1));
        assertTrue(jdbc.sql.get(1).contains("ORDER BY f.create_time DESC,f.id DESC LIMIT ? OFFSET ?"));
        assertTrue(jdbc.sql.get(1).contains("o.user_id=f.user_id"));
    }

    @Test public void unfilteredPageAndInvalidRequestsNeverRelaxIdentityOrBounds() {
        service.flows(42, null, null, null, 1, 10);
        assertEquals(List.of(42L), jdbc.arguments.getFirst());
        assertEquals(List.of(42L, 10, 0L), jdbc.arguments.get(1));
        jdbc.sql.clear();
        assertThrows(ServiceException.class, () -> service.account(0));
        assertThrows(ServiceException.class, () -> service.flows(0, null, null, null, 1, 10));
        for (int size : new int[] {0, -1, 101})
            assertThrows(ServiceException.class, () -> service.flows(42, null, null, null, 1, size));
        assertThrows(ServiceException.class, () -> service.flows(42, null, null, null, 0, 10));
        assertThrows(ServiceException.class, () -> service.flows(42, " ", null, null, 1, 10));
        var now = LocalDateTime.now();
        assertThrows(ServiceException.class, () -> service.flows(42, null, now, now, 1, 10));
        assertTrue(jdbc.sql.isEmpty());
    }

    @Test public void directionalContractAndLegacySignedAmountsAreNotNaivelySummed() {
        var freeze = flow("freeze_self", "10", "REBATE_COST", "USER_FROZEN", null);
        assertMoney("10", freeze.frozenDelta()); assertMoney("0", freeze.availableDelta());
        var release = flow("income_self", "10", "USER_FROZEN", "USER_AVAILABLE", null);
        assertMoney("-10", release.frozenDelta()); assertMoney("10", release.availableDelta());
        assertMoney("0", release.userDelta());
        var reserve = flow("withdraw", "10", "USER_AVAILABLE", "USER_WITHDRAWING", null);
        assertMoney("-10", reserve.availableDelta()); assertMoney("10", reserve.withdrawingDelta());
        assertMoney("0", reserve.userDelta());
        var paid = flow("withdraw", "10", "USER_WITHDRAWING", "PAYOUT", null);
        assertMoney("-10", paid.userDelta()); assertMoney("10", paid.paidDelta());
        var returned = flow("withdraw", "10", "USER_WITHDRAWING", "USER_AVAILABLE", null);
        assertMoney("0", returned.userDelta()); assertMoney("10", returned.availableDelta());
        var recovery = flow("refund_self", "10", "USER_AVAILABLE", "REBATE_COST", null);
        assertMoney("-10", recovery.userDelta());
        assertMoney("-3", flow("refund_self", "-3", null, null, null).userDelta());
        assertMoney("4", flow("income_self", "4", null, null, null).userDelta());
        var legacyPaid = flow("withdraw", "-5", null, null, null);
        assertMoney("-5", legacyPaid.userDelta()); assertMoney("5", legacyPaid.paidDelta());
        var reversal = flow("reversal", "5", null, null, "withdraw");
        assertMoney("5", reversal.userDelta()); assertMoney("-5", reversal.paidDelta());
    }

    private static WalletFlow flow(String type, String amount, String debit, String credit, String original) {
        return WalletFlow.of(1, type, null, new BigDecimal(amount), debit, credit, original);
    }
    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
    private static class RecordingJdbc extends JdbcTemplate {
        final List<String> sql = new ArrayList<>();
        final List<List<Object>> arguments = new ArrayList<>();
        ResultSet row;
        private void record(String query, Object[] args) {
            assertTrue("Only SELECT is permitted", query.stripLeading().startsWith("SELECT "));
            sql.add(query); arguments.add(List.of(args));
        }
        @Override public <T> List<T> query(String query, RowMapper<T> mapper, Object... args) {
            record(query, args);
            try { return row == null ? List.of() : List.of(mapper.mapRow(row, 0)); }
            catch (SQLException e) { throw new AssertionError(e); }
        }
        @Override public <T> T queryForObject(String query, Class<T> type, Object... args) {
            record(query, args); return type.cast(7L);
        }
    }
}
