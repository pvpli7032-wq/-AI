package com.ruoyi.system.cps.settlement;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.union.adapter.*;
import org.junit.Test;

public class ConfiguredSettlementFreezePortTest {
    final ConfiguredSettlementFreezePort adapter=new ConfiguredSettlementFreezePort();
    final Instant paid=Instant.parse("2026-01-01T00:00:00Z");
    final SettlementTermsPort.Benefit benefit=new SettlementTermsPort.Benefit("REBATE",1L,new BigDecimal("5.00"),1L);
    SettlementTermsPort.Terms terms(boolean disabled) {
        String config=JSON.toJSONString(new ConfiguredSettlementFreezePort.Configuration(
            Map.of(Platform.TBK,List.of(new ConfiguredSettlementFreezePort.Band(BigDecimal.ZERO,172800L),
                new ConfiguredSettlementFreezePort.Band(new BigDecimal("10"),345600L))),Map.of(Platform.TBK,3600L),disabled));
        return new SettlementTermsPort.Terms(List.of(benefit),new BigDecimal("100"),config,BigDecimal.ZERO,"{}");
    }
    UnionOrderLine line(Instant received) {
        return new UnionOrderLine(Platform.TBK,"order","line","item","pid",null,BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN,
            "raw",paid,received,received==null ? paid.plusSeconds(200000) : received.plusSeconds(100));
    }
    @Test public void actualFastReceiptUsesColleaguePolicy() {
        assertEquals(Duration.ofHours(1),adapter.duration(terms(false),line(paid.plusSeconds(3600)),benefit));
    }
    @Test public void missingReceiptDoesNotInventFastReceipt() {
        assertEquals(Duration.ofDays(2),adapter.duration(terms(false),line(null),benefit));
    }
    @Test public void explicitDisabledPolicyHasZeroDuration() {
        assertEquals(Duration.ZERO,adapter.duration(terms(true),line(null),benefit));
    }
    @Test public void missingConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class,() -> adapter.duration(
            new SettlementTermsPort.Terms(List.of(benefit),BigDecimal.TEN,"{}",BigDecimal.ZERO,"{}"),line(null),benefit));
    }
}
