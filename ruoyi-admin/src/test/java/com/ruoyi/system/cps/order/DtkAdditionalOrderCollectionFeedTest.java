package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderAuxiliaryEvidenceMapper;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class DtkAdditionalOrderCollectionFeedTest {
    private static final Instant NOW = Instant.parse("2026-01-01T01:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW.minusSeconds(3600), NOW);
    private final OrderIngestionService ingestion = mock(OrderIngestionService.class);
    private final OrderCollectionSettings settings = mock(OrderCollectionSettings.class);
    private final OrderAuxiliaryEvidenceMapper evidence = mock(OrderAuxiliaryEvidenceMapper.class);
    private final List<String> urls = new ArrayList<>();
    private DtkAdditionalOrderCollectionFeed feed(String... responses) {
        AtomicLong id = new AtomicLong();
        when(ingestion.retainRaw(any(), anyString(), any())).thenAnswer(a -> id.incrementAndGet());
        var client = new DtkOrderEvidenceClient("test-key", "test-secret", Duration.ofSeconds(1), (uri, timeout) -> {
            urls.add(uri.toString());
            if (urls.size() > responses.length) throw new IllegalStateException("unexpected request");
            return responses[urls.size() - 1];
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        return new DtkAdditionalOrderCollectionFeed(client, ingestion, settings, evidence, ZoneOffset.UTC,
            Clock.fixed(NOW, ZoneOffset.UTC), "jd-key", 2);
    }
    private static String jd(String more, String code, String extra) {
        return "{\"code\":0,\"data\":{\"hasMore\":" + more + ",\"list\":[{\"orderId\":\"o\",\"id\":\"sub\","
            + "\"skuId\":\"sku\",\"pid\":\"pid\",\"siteId\":\"site\",\"validCode\":\"" + code + "\","
            + "\"modifyTime\":\"2026-01-01 00:20:00\",\"price\":\"12.34\",\"skuNum\":\"2\",\"actualFee\":\"1.50\""
            + extra + "}]}}";
    }
    @Test public void parsesAllThreeHasMoreRepresentationsWithoutGuessing() {
        for (Object value : List.of(1, true, "true", "1")) assertTrue(DtkOrderSemantics.hasMore(value));
        for (Object value : List.of(0, false, "false", "0")) assertFalse(DtkOrderSemantics.hasMore(value));
        for (Object value : List.of(2, "unknown", "", new BigDecimal("0.5")))
            assertThrows(IllegalStateException.class, () -> DtkOrderSemantics.hasMore(value));
        assertThrows(IllegalStateException.class, () -> DtkOrderSemantics.hasMore(null));
    }
    @Test public void jdUsesTypeThreeAndFinishesEveryPageAndRetainsExactEvidence() {
        String one = jd("1", "16", ""), two = jd("true", "17", ""), three = jd("\"false\"", "16", "");
        var result = feed(one, two, three).fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL, WINDOW, null);
        assertEquals(3, result.entries().size()); assertTrue(result.completenessConfirmed());
        assertEquals(one, result.entries().get(0).incoming().rawJson());
        assertEquals(OrderStatus.PAID, result.entries().get(0).incoming().resolvedStatus());
        assertEquals(new BigDecimal("24.68"), result.entries().get(0).incoming().line().paidAmount());
        assertEquals(OrderStatus.RECEIVED, result.entries().get(1).incoming().resolvedStatus());
        for (int i = 0; i < 3; i++) { assertTrue(urls.get(i).contains("type=3")); assertTrue(urls.get(i).contains("pageNo=" + (i + 1))); }
        verify(ingestion).retainRaw(Platform.JD, one, NOW); verify(ingestion).retainRaw(Platform.JD, two, NOW);
    }
    @Test public void malformedHasMoreAndOversizeWindowFailInsteadOfCompleting() {
        var feed = feed(jd("\"unknown\"", "16", ""));
        assertThrows(IllegalStateException.class, () -> feed.fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL, WINDOW, null));
        verify(ingestion).recordSourceException(anyLong(), contains("hasMore"));
        assertThrows(IllegalArgumentException.class, () -> feed.fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL,
            new TimeWindow(NOW.minusSeconds(3601), NOW), null));
    }
    @Test public void jdOverLimitSplitsWindowAndRetriesBothHalves() {
        var result = feed("{\"code\":2002452}", jd("false", "16", ""), jd("0", "17", ""))
            .fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.BACKFILL, WINDOW, null);
        assertEquals(2, result.entries().size()); assertEquals(3, urls.size()); assertTrue(result.completenessConfirmed());
        assertTrue(urls.get(1).contains("endTime=2026-01-01+00%3A30%3A00"));
        assertTrue(urls.get(2).contains("startTime=2026-01-01+00%3A30%3A00"));
    }
    @Test public void documentedJdCodesAndPayMonthNeverGuessUnconfirmedValues() {
        LocalDate today = LocalDate.of(2026, 1, 1);
        for (int code = 2; code <= 14; code++) assertEquals(OrderStatus.INVALID, DtkOrderSemantics.jd("" + code, null, today));
        assertEquals(OrderStatus.PAID, DtkOrderSemantics.jd("16", "0", today));
        assertEquals(OrderStatus.RECEIVED, DtkOrderSemantics.jd("17", "202701", today));
        assertEquals(OrderStatus.SETTLED, DtkOrderSemantics.jd("17", "202512", today));
        assertEquals(OrderStatus.RECEIVED, DtkOrderSemantics.jd("17", "20260101", today));
        assertEquals(OrderStatus.SETTLED, DtkOrderSemantics.jd("17", "2025-12-31", today));
        assertNull(DtkOrderSemantics.jd("15", "202512", today));
        for (int code = 18; code <= 28; code++) assertNull(DtkOrderSemantics.jd("" + code, "202512", today));
        assertNull(DtkOrderSemantics.jd("999", null, today));
        assertThrows(RuntimeException.class, () -> DtkOrderSemantics.jd("16", "nonsense", today));
    }
    @Test public void unknownJdIsArchivedAndFlaggedWithoutBlockingValidSiblingsInTheFeed() {
        var result = feed(jd("true", "18", ""), jd("false", "16", ""))
            .fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL, WINDOW, null);
        assertFalse(result.completenessConfirmed()); assertEquals(1, result.entries().size());
        verify(ingestion).recordSourceException(anyLong(), contains("validCode=18"));
    }
    @Test public void jdAfterSaleUsesDisputeOrQuantityBasedRefundAndNeverSkipsSettledOrders() {
        var result = feed(jd("true", "17", ",\"payMonth\":\"202512\",\"skuFrozenNum\":1"),
            jd("true", "17", ",\"skuReturnNum\":1"), jd("false", "17", ",\"skuReturnNum\":2"))
            .fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL, WINDOW, null);
        assertEquals(OrderStatus.DISPUTED, result.entries().get(0).incoming().resolvedStatus());
        assertEquals(OrderStatus.PARTIAL_REFUND, result.entries().get(1).incoming().resolvedStatus());
        assertEquals(OrderStatus.FULL_REFUND, result.entries().get(2).incoming().resolvedStatus());
    }
    private static String pdd(String total, String rows) { return "{\"code\":0,\"data\":{\"totalNum\":" + total + ",\"pageId\":\"opaque\",\"list\":[" + rows + "]}}"; }
    private static String pddRow(String code) { return "{\"orderSN\":\"pdd-order\",\"pid\":\"pdd-pid\",\"orderStatus\":\"" + code
        + "\",\"orderAmount\":1234,\"promotionAmount\":123,\"orderModifyAt\":1767225600}"; }
    @Test public void pddDoesNotInventCursorSemanticsAndIncompleteTotalKeepsWindowFailed() {
        var result = feed(pdd("2", pddRow("0"))).fetch(Platform.PDD, OrderCollectionRetryQueue.TaskType.INCREMENTAL, WINDOW, null);
        assertFalse(result.completenessConfirmed()); assertEquals(1, urls.size());
        assertFalse(urls.get(0).contains("pageId=")); assertTrue(urls.get(0).contains("pageSize=100"));
        assertEquals(new BigDecimal("12.34"), result.entries().get(0).incoming().line().paidAmount());
        assertEquals("", result.entries().get(0).siteId());
        verify(ingestion).recordSourceException(anyLong(), contains("totalNum"));
    }
    @Test public void pddExactTotalIsCompleteButAbsentTotalAndOversizeWindowAreNot() {
        var feed = feed(pdd("1", pddRow("1")), pdd("null", pddRow("0")));
        assertTrue(feed.fetch(Platform.PDD, OrderCollectionRetryQueue.TaskType.BACKFILL, WINDOW, null).completenessConfirmed());
        assertFalse(feed.fetch(Platform.PDD, OrderCollectionRetryQueue.TaskType.BACKFILL, WINDOW, null).completenessConfirmed());
        assertThrows(IllegalArgumentException.class, () -> feed.fetch(Platform.PDD, OrderCollectionRetryQueue.TaskType.BACKFILL,
            new TimeWindow(NOW.minusSeconds(86401), NOW), null));
        assertEquals(OrderStatus.RECEIVED, DtkOrderSemantics.pdd("3"));
        assertEquals(OrderStatus.INVALID, DtkOrderSemantics.pdd("4"));
        assertEquals(OrderStatus.INVALID, DtkOrderSemantics.pdd("8")); assertNull(DtkOrderSemantics.pdd("-1"));
    }
    private static String cases(int total, String row) { return "{\"code\":0,\"data\":{\"total_count\":" + total + ",\"results\":[" + row + "]}}"; }
    private static String penalty(String status, String violation) { return "{\"tb_trade_id\":\"order\",\"tk_pub_id\":1,\"tk_site_id\":2,\"tk_adzone_id\":3,\"punish_status\":\""
        + status + "\",\"violation_type\":\"" + violation + "\"}"; }
    @Test public void penaltyPagesAcceptNumericAndChineseViolationAndOnlyImportEvidence() {
        when(settings.belongsToSystem(any(), any())).thenReturn(true);
        var result = feed(cases(2, penalty("0", "1")), cases(2, penalty("1", "成交违规")))
            .fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.PENALTY, WINDOW, null);
        assertTrue(result.completenessConfirmed()); assertTrue(result.entries().isEmpty()); assertEquals(2, urls.size());
        var capture = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(evidence, times(2)).appendAutomaticPenalty(capture.capture());
        assertEquals("1", capture.getAllValues().get(0).get("violation"));
        assertEquals("成交违规", capture.getAllValues().get(1).get("violation"));
        verify(ingestion, never()).ingest(any()); assertTrue(urls.get(1).contains("pageNo=2"));
    }
    @Test public void foreignPenaltyNeverChangesOrdersOrImportsAndThirtyDayLimitIsEnforced() {
        var feed = feed(cases(1, penalty("0", "2")));
        assertTrue(feed.fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.PENALTY, WINDOW, null).completenessConfirmed());
        verify(settings).auditRejected(any(), any()); verifyNoInteractions(evidence);
        assertThrows(IllegalArgumentException.class, () -> feed.fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.PENALTY,
            new TimeWindow(NOW.minusSeconds(2592001), NOW), null));
    }
    private void storedTbk() {
        when(evidence.findTbk(anyMap())).thenReturn(Map.of("pid", "mm_1_2_3", "item_id", "item",
            "order_amount", new BigDecimal("100"), "actual_commission", new BigDecimal("5")));
    }
    private static String refund(String code, String amount) { return "{\"tb_trade_parent_id\":\"parent\",\"tb_trade_id\":\"child\",\"tk_pub_id\":1,"
        + "\"refund_status\":\"" + code + "\",\"refund_fee\":\"" + amount + "\",\"tb_trade_finish_price\":\"100\",\"modified_time\":\"2026-01-01 00:10:00\"}"; }
    @Test public void refundRequiredFieldsPaginationAndPartialFullComparisonsAreExplicit() {
        storedTbk();
        var result = feed(cases(2, refund("2", "20")), cases(2, refund("13", "100")))
            .fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.REFUND, WINDOW, null);
        assertTrue(result.completenessConfirmed()); assertEquals(2, result.entries().size());
        assertEquals(OrderStatus.PARTIAL_REFUND, result.entries().get(0).incoming().resolvedStatus());
        assertEquals(OrderStatus.FULL_REFUND, result.entries().get(1).incoming().resolvedStatus());
        assertNull(result.entries().get(0).recoveredAmount()); // Gross refund is NOT the recovered user rebate.
        assertEquals(new BigDecimal("5"), result.entries().get(0).incoming().line().actualCommission());
        for (String url : urls) { assertTrue(url.contains("searchType=5")); assertTrue(url.contains("refundType=0")); assertTrue(url.contains("bizType=2")); assertTrue(url.contains("startTime=")); }
    }
    @Test public void refundPendingAndRestoreUsePersistedStateWhileSellerDeductionsDoNotAdvance() {
        storedTbk();
        var result = feed(cases(3, refund("11", "20")), cases(3, refund("3", "20")), cases(3, refund("14", "20")))
            .fetch(Platform.TBK, OrderCollectionRetryQueue.TaskType.REFUND, WINDOW, null);
        assertFalse(result.completenessConfirmed()); assertEquals(2, result.entries().size());
        assertEquals(OrderStatus.DISPUTED, result.entries().get(0).incoming().resolvedStatus());
        assertTrue(result.entries().get(1).incoming().restorePrevious());
        verify(ingestion).recordSourceException(anyLong(), contains("refund_status=14"));
    }
    @Test public void missingStatusTimeIsNeverFilledWithCollectionTime() {
        String raw = jd("false", "16", "").replace(",\"modifyTime\":\"2026-01-01 00:20:00\"", "");
        var result = feed(raw).fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL, WINDOW, null);
        assertNull(result.entries().get(0).incoming().allianceStatusTime());
        assertNull(result.entries().get(0).incoming().line().allianceStatusTime());
    }
    @Test public void receivedAsSettledIsDefaultOffAndDoesNotChangeContractTransitions() {
        assertEquals(OrderStatus.RECEIVED, new PlatformOrderStatusMapper().map(Platform.TBK, "14"));
        assertEquals(OrderStatus.SETTLED, new PlatformOrderStatusMapper(() -> true).map(Platform.TBK, "14"));
        var validator = new OrderTransitionValidator();
        assertEquals(OrderTransitionValidator.Outcome.APPLY, validator.evaluate(OrderStatus.PAID, OrderStatus.SETTLED, null).outcome());
        assertEquals(OrderTransitionValidator.Outcome.APPLY, validator.evaluate(OrderStatus.RECEIVED, OrderStatus.SETTLED, null).outcome());
    }
}
