package com.ruoyi.system.cps.settlement;

import static org.junit.Assert.*;
import org.junit.Test;

public class PddSettlementEvidenceTest {
    @Test public void onlyOneMatchingOrderWithExplicitComparisonOneConfirmsReason() {
        assertTrue(SettlementPddNoticeService.confirmedPriceComparison(
            "{\"data\":[{\"order_sn\":\"other\",\"price_compare_status\":0},{\"order_sn\":\"ours\",\"price_compare_status\":1}]}", "ours"));
        assertTrue(SettlementPddNoticeService.confirmedPriceComparison(
            "{\"orderSN\":\"ours\",\"priceCompareStatus\":1}", "ours"));
    }
    @Test public void missingUnknownConflictingOrDuplicatedEvidenceNeverGuessesComparison() {
        for (String raw:java.util.List.of("{}","not json",
                "{\"order_sn\":\"other\",\"price_compare_status\":1}",
                "{\"order_sn\":\"ours\",\"price_compare_status\":2}",
                "{\"order_sn\":\"ours\",\"price_compare_status\":true}",
                "{\"order_sn\":\"ours\",\"orderSN\":\"other\",\"price_compare_status\":1}",
                "{\"order_sn\":\"ours\",\"priceCompareStatus\":1,\"price_compare_status\":0}",
                "[{\"order_sn\":\"ours\",\"price_compare_status\":1},{\"order_sn\":\"ours\",\"price_compare_status\":1}]")) {
            assertFalse(raw,SettlementPddNoticeService.confirmedPriceComparison(raw,"ours"));
        }
    }
}
