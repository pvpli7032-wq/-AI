package com.ruoyi.system.cps.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderAuxiliaryEvidenceMapper;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Invalid provider codes retain their nonsettling meaning despite after-sale quantities. */
public class JdInvalidStatusPrecedenceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T01:00:00Z");

    private static void assertTarget(int code, int returned, int frozen, OrderStatus expected) {
        String raw = "{\"code\":0,\"data\":{\"hasMore\":false,\"list\":[{"
            + "\"orderId\":\"order\",\"id\":\"sub\",\"validCode\":\"" + code + "\","
            + "\"modifyTime\":\"2026-01-01 00:20:00\",\"price\":\"10\",\"skuNum\":2,"
            + "\"skuReturnNum\":" + returned + ",\"skuFrozenNum\":" + frozen + "}]}}";
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var client = new DtkOrderEvidenceClient("test-key", "test-secret", Duration.ofSeconds(1),
            (uri, timeout) -> raw, clock);
        var feed = new DtkAdditionalOrderCollectionFeed(client, mock(OrderIngestionService.class),
            mock(OrderCollectionSettings.class), mock(OrderAuxiliaryEvidenceMapper.class),
            ZoneOffset.UTC, clock, "jd-key", 2);
        var page = feed.fetch(Platform.JD, OrderCollectionRetryQueue.TaskType.INCREMENTAL,
            new TimeWindow(NOW.minusSeconds(3600), NOW), null);
        assertTrue(page.completenessConfirmed());
        assertEquals(1, page.entries().size());
        assertEquals("validCode=" + code + ", returned=" + returned + ", frozen=" + frozen,
            expected, page.entries().get(0).incoming().resolvedStatus());
        assertEquals(Integer.toString(code), page.entries().get(0).incoming().line().rawStatus());
    }

    @Test public void invalidCodesAreNotOverriddenByReturnedOrFrozenQuantities() {
        for (int code = 2; code <= 14; code++) {
            assertTarget(code, 1, 0, OrderStatus.INVALID);
            assertTarget(code, 2, 0, OrderStatus.INVALID);
            assertTarget(code, 0, 1, OrderStatus.INVALID);
            assertTarget(code, 1, 1, OrderStatus.INVALID);
        }
    }

    @Test public void knownValidTargetsStillUseQuantityOverrides() {
        for (int code : new int[] {16, 17}) {
            assertTarget(code, 1, 0, OrderStatus.PARTIAL_REFUND);
            assertTarget(code, 2, 0, OrderStatus.FULL_REFUND);
            assertTarget(code, 0, 1, OrderStatus.DISPUTED);
        }
    }
}
