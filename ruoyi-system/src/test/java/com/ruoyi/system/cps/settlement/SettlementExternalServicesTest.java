package com.ruoyi.system.cps.settlement;

import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import org.junit.Test;

/** Typed bridge contract tests; no database or delivery assertions. */
public class SettlementExternalServicesTest {
    @Test public void lossPhasesUseOnlyOutboxSupportedReasonCodes() {
        SettlementNotificationService outbox=mock(SettlementNotificationService.class);
        SettlementExternalServices bridge=new SettlementExternalServices(mock(PaymentInvitationSnapshotService.class),outbox);
        BigDecimal amount=new BigDecimal("2.00"),balance=new BigDecimal("-1.00");
        bridge.notify("refund",1L,2L,"RECOVER",amount,balance);
        bridge.notify("penalty",1L,2L,"PENALTY_RECOVER",amount,balance);
        bridge.notify("reprice",1L,2L,"REPRICE_DOWN",amount,balance);
        verify(outbox).enqueue("refund",1L,2L,SettlementNotificationService.REFUND,amount,balance);
        verify(outbox).enqueue("penalty",1L,2L,SettlementNotificationService.WHOLE_MONTH_PENALTY,amount,balance);
        verify(outbox).enqueue("reprice",1L,2L,SettlementNotificationService.REBATE_INVALID,amount,balance);
        verifyNoMoreInteractions(outbox);
    }
    @Test public void positiveTransfersDoNotPretendToBeLossNotifications() {
        SettlementNotificationService outbox=mock(SettlementNotificationService.class);
        SettlementExternalServices bridge=new SettlementExternalServices(mock(PaymentInvitationSnapshotService.class),outbox);
        for (String phase:new String[]{"FREEZE","RELEASE","REWARD","REWARD_FREEZE","REPRICE_UP"})
            bridge.notify(phase,1L,2L,phase,BigDecimal.ZERO,BigDecimal.TEN);
        verifyNoInteractions(outbox);
    }
}
