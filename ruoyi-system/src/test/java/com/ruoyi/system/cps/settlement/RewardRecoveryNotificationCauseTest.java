package com.ruoyi.system.cps.settlement;

import com.ruoyi.system.cps.ledger.LedgerPosting;
import com.ruoyi.system.cps.ledger.LedgerService;
import com.ruoyi.system.cps.rule.RewardApi;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Unit boundary proof only; delivery-channel and SQL acceptance are separate. */
public class RewardRecoveryNotificationCauseTest {
    @Test public void penaltyRewardRecoveryRetainsPenaltyCause() { check("PENALTY_RECOVER"); }
    @Test public void ordinaryRewardRecoveryRetainsRefundCause() { check("RECOVER"); }

    @Test public void replayDoesNotRelabelPenaltyOutboxAsRefund() { check("reward-key","RECOVER",true); }
    @Test public void repricingRecoveryHasStableAdjustmentCause() { check("RP:synthetic","RECOVER",false); }

    private void check(String phase) { check("reward-key",phase,false); }
    private void check(String key,String phase,boolean replayed) {
        LedgerService ledger=mock(LedgerService.class);
        SettlementExternalServices external=mock(SettlementExternalServices.class);
        when(ledger.post(any())).thenReturn(new LedgerPosting(1L,key,new BigDecimal("-2.00"),replayed));
        DefaultSettlementService service=new DefaultSettlementService(null,ledger,null,null,external,null,null,
            mock(PlatformTransactionManager.class));
        List<Object> transfers=new ArrayList<>();
        RewardApi.Instruction instruction=new RewardApi.Instruction(key,10L,"award",20L,10L,
            RewardApi.Type.FIRST_N_SUBSIDY,true,new BigDecimal("3.00"),BigDecimal.ONE);
        ReflectionTestUtils.invokeMethod(service,"rewardTransfers",transfers,List.of(instruction),phase);
        ReflectionTestUtils.invokeMethod(service,"apply",transfers);
        if (replayed) verifyNoInteractions(external);
        else verify(external).notify(key,20L,10L,key.startsWith("RP:") ? "REPRICE_DOWN" : phase,
            new BigDecimal("3.00"),new BigDecimal("-2.00"));
        verify(ledger,times(1)).post(any());
    }
}
