package com.ruoyi.system.cps.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class DtkOrderCollectionFeedTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:30:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(NOW.minusSeconds(1800), NOW);
    private static final OrderCollectionRetryQueue.TaskType TYPE = OrderCollectionRetryQueue.TaskType.INCREMENTAL;
    private static String response(boolean more, String position, boolean time) {
        return "{\"code\":0,\"data\":{\"has_next\":" + more + ",\"position_index\":\"" + position
            + "\",\"results\":{\"publisher_order_dto\":[{\"trade_parent_id\":\"parent\",\"trade_id\":\"child\","
            + "\"item_id\":\"item\",\"pub_id\":1,\"site_id\":2,\"adzone_id\":3,\"tk_status\":12,"
            + "\"alipay_total_price\":\"12.34\",\"pub_share_pre_fee\":\"1.2345\",\"tb_paid_time\":\"2026-01-01 00:01:00\""
            + (time ? ",\"modified_time\":\"2026-01-01 00:02:00\"" : "") + "}]}}}";
    }
    @Test public void allPagesCarryActualResponseAndProviderCursorWithoutReserializing() {
        List<String> urls = new ArrayList<>();
        List<String> raw = List.of(response(true, "position+/=1", true), response(true, "position2", true), response(false, "", true));
        OrderIngestionService ingestion = mock(OrderIngestionService.class);
        var client = new DtkOrderEvidenceClient("fake", "secret", Duration.ofSeconds(1), (uri, timeout) -> {
            urls.add(uri.toString()); return raw.get(urls.size() - 1);
        }, Clock.fixed(NOW, ZoneOffset.UTC));
        var feed = new DtkOrderCollectionFeed(client, ingestion, ZoneOffset.UTC, 1);
        String cursor = null;
        for (int index = 0; index < 3; index++) {
            var page = feed.fetch(Platform.TBK, TYPE, WINDOW, cursor);
            assertEquals(1, page.entries().size());
            IncomingOrder incoming = page.entries().get(0).incoming();
            assertEquals(raw.get(index), incoming.line().rawResponseJson());
            assertEquals(Instant.parse("2026-01-01T00:02:00Z"), incoming.line().allianceStatusTime());
            assertEquals("parent", incoming.line().orderNo()); assertEquals("child", incoming.line().subOrderNo());
            assertEquals("mm_1_2_3", incoming.line().pid()); assertEquals("2", page.entries().get(0).siteId());
            assertNull(incoming.line().receivedAt());
            cursor = page.nextCursor();
        }
        assertNull(cursor);
        assertFalse(urls.get(0).contains("positionIndex="));
        assertTrue(urls.get(1).contains("positionIndex=position%2B%2F%3D1"));
        assertTrue(urls.get(1).contains("jumpType=1"));
        assertTrue(urls.get(2).contains("pageNo=3"));
        for (String text : raw) verify(ingestion).retainRaw(Platform.TBK, text, NOW);
    }
    @Test public void missingModifiedTimeStaysNullInsteadOfUsingPaymentOrFetchTime() {
        var client = new DtkOrderEvidenceClient("fake", "secret", Duration.ofSeconds(1),
            (uri, timeout) -> response(false, "", false), Clock.fixed(NOW, ZoneOffset.UTC));
        var feed = new DtkOrderCollectionFeed(client, mock(OrderIngestionService.class), ZoneOffset.UTC, 1);
        var incoming = feed.fetch(Platform.TBK, TYPE, WINDOW, null).entries().get(0).incoming();
        assertNull(incoming.allianceStatusTime()); assertNull(incoming.line().allianceStatusTime());
        assertNotNull(incoming.line().paidAt()); assertEquals(NOW, incoming.fetchedAt());
    }
    @Test public void unsupportedFeedsAndUnusableCursorCannotLookLikeSuccessfulEmptyPages() {
        OrderIngestionService ingestion = mock(OrderIngestionService.class);
        var client = new DtkOrderEvidenceClient("fake", "secret", Duration.ofSeconds(1),
            (uri, timeout) -> response(true, "", true), Clock.fixed(NOW, ZoneOffset.UTC));
        var feed = new DtkOrderCollectionFeed(client, ingestion, ZoneOffset.UTC, 1);
        assertFalse(feed.supports(Platform.PDD, TYPE)); assertFalse(feed.supports(Platform.JD, TYPE));
        assertThrows(IllegalArgumentException.class, () -> feed.fetch(Platform.PDD, TYPE, WINDOW, null));
        assertThrows(IllegalStateException.class, () -> feed.fetch(Platform.TBK, TYPE, WINDOW, null));
        verify(ingestion).retainRaw(Platform.TBK, response(true, "", true), NOW);
    }
}
