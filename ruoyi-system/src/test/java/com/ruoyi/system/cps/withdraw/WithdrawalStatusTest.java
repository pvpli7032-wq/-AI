package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/** The raw backend state and miniapp display are separate contracts, including ambiguous outcomes. */
public class WithdrawalStatusTest {
    private static final Map<WithdrawalStatus, String> MINIAPP_LABELS = Map.of(
        WithdrawalStatus.MANUAL_REVIEW, "审核中",
        WithdrawalStatus.APPROVED, "打款中",
        WithdrawalStatus.SUBMITTING, "打款中",
        WithdrawalStatus.PROCESSING, "打款中",
        WithdrawalStatus.UNKNOWN, "打款中",
        WithdrawalStatus.AWAITING_CONFIRMATION, "打款中",
        WithdrawalStatus.TIMED_OUT, "打款中",
        WithdrawalStatus.SUCCEEDED, "已打款",
        WithdrawalStatus.FAILED, "打款失败",
        WithdrawalStatus.REJECTED, "审核驳回");

    @Test
    public void allTenStatesHaveDistinctAdminOptionsAndExactMiniappLabels() {
        List<WithdrawalStatus.Option> options = WithdrawalStatus.options();
        assertEquals(10, options.size());
        assertEquals(MINIAPP_LABELS.keySet(), options.stream()
            .map(option -> WithdrawalStatus.valueOf(option.value())).collect(Collectors.toSet()));
        assertEquals("Admin filters must not collapse the six pending states", 10,
            options.stream().map(WithdrawalStatus.Option::label).distinct().count());
        for (WithdrawalStatus.Option option : options) {
            WithdrawalStatus status = WithdrawalStatus.valueOf(option.value());
            assertEquals(option.value(), MINIAPP_LABELS.get(status), option.miniappLabel());
            assertEquals(option.value(), status == WithdrawalStatus.FAILED || status == WithdrawalStatus.REJECTED,
                option.fundsReturned());
        }
        assertThrows(UnsupportedOperationException.class, () -> options.removeFirst());
    }

    @Test
    public void viewsKeepRawStatusAndOnlyFinalFailuresPromiseReturnedFunds() {
        for (WithdrawalStatus status : WithdrawalStatus.values()) {
            WithdrawalView view = WithdrawalView.of(row(status));
            assertEquals(status, view.status());
            assertEquals(status.name(), MINIAPP_LABELS.get(status), view.statusText());
            assertEquals(new BigDecimal("12.34"), view.amount());
            assertEquals(new BigDecimal("0.00"), view.fee());
            assertEquals("****1234", view.recipient());
            boolean returned = Set.of(WithdrawalStatus.FAILED, WithdrawalStatus.REJECTED).contains(status);
            assertEquals(status.name(), returned, view.fundsReturned());
            assertEquals(status.name(), returned, view.message().contains("金额已退回余额"));
            if (!status.terminal()) assertFalse(status.name(), view.message().contains("已打款"));
        }
    }

    @Test
    public void originalConstructorKeepsItsFieldsAndReceivesTheNewDisplayFields() {
        LocalDateTime created = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (WithdrawalStatus status : WithdrawalStatus.values()) {
            WithdrawalView view = new WithdrawalView("synthetic-original-" + status, new BigDecimal("1.00"),
                new BigDecimal("0.00"), status, "mock", "****1234", created, created, "original message");
            assertEquals("synthetic-original-" + status, view.withdrawNo());
            assertEquals("original message", view.message());
            assertEquals(created, view.createdAt());
            assertEquals(created, view.updatedAt());
            assertEquals(MINIAPP_LABELS.get(status), view.statusText());
            assertEquals(status == WithdrawalStatus.FAILED || status == WithdrawalStatus.REJECTED, view.fundsReturned());
        }
    }

    private static WithdrawalRecord row(WithdrawalStatus status) {
        WithdrawalRecord row = new WithdrawalRecord();
        row.setWithdrawNo("synthetic-status-" + status.name());
        row.setAmount(new BigDecimal("12.34"));
        row.setFee(new BigDecimal("0.00"));
        row.setStatus(status);
        row.setChannel("mock");
        row.setRecipientAccount("synthetic-payee-1234");
        row.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        row.setUpdatedAt(row.getCreatedAt());
        return row;
    }
}
