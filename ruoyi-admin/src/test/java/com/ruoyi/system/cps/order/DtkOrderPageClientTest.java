package com.ruoyi.system.cps.order;

import com.ruoyi.union.adapter.DtkOrderPageClient;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.dtk.api.client.DtkClient;
import com.ruoyi.union.dtk.api.client.AbstractDtkApiClient;
import com.ruoyi.union.dtk.api.request.mastertool.DtkGetOrderDetailsRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkJdOrderQueryV2Request;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkGetOrderDetailsResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkJdOrderQueryV2Response;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DtkOrderPageClientTest {
    private static final TimeWindow WINDOW = new TimeWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:30:00Z"));

    @Test public void tbkContinuationPassesPositionAndPage() {
        DtkClient api = mock(DtkClient.class);
        DtkGetOrderDetailsResponse data = new DtkGetOrderDetailsResponse();
        data.setHasNext(true);
        data.setPositionIndex("next-position");
        when(api.execute(any(DtkGetOrderDetailsRequest.class))).thenReturn(DtkApiResponse.buildSuccess(data));
        DtkOrderPageClient client = new DtkOrderPageClient(api, ZoneOffset.UTC);
        assertSame(data, client.fetchTbk(WINDOW, 1, 2, "previous-position"));
        ArgumentCaptor<DtkGetOrderDetailsRequest> request = ArgumentCaptor.forClass(DtkGetOrderDetailsRequest.class);
        verify(api).execute(request.capture());
        assertEquals("previous-position", request.getValue().getPositionIndex());
        assertEquals(Integer.valueOf(2), request.getValue().getPageNo());
        assertEquals(Integer.valueOf(1), request.getValue().getJumpType());
    }

    @Test public void jdPassesPageAndRejectsUnusablePagination() {
        DtkClient api = mock(DtkClient.class);
        DtkJdOrderQueryV2Response data = new DtkJdOrderQueryV2Response();
        data.setHasMore(1);
        when(api.execute(any(DtkJdOrderQueryV2Request.class))).thenReturn(DtkApiResponse.buildSuccess(data));
        DtkOrderPageClient client = new DtkOrderPageClient(api, ZoneOffset.UTC);
        assertSame(data, client.fetchJd(WINDOW, 3, "test-only-authorization"));
        ArgumentCaptor<DtkJdOrderQueryV2Request> request = ArgumentCaptor.forClass(DtkJdOrderQueryV2Request.class);
        verify(api).execute(request.capture());
        assertEquals(Integer.valueOf(3), request.getValue().getPageNo());
        assertEquals("test-only-authorization", request.getValue().getKey());
        assertNull(request.getValue().getChildUnionId());
        data.setHasMore(null);
        assertThrows(IllegalStateException.class, () -> client.fetchJd(WINDOW, 4, "test-only-authorization"));
        assertThrows(IllegalArgumentException.class, () -> client.fetchJd(WINDOW, 4, " "));
    }

    @Test public void providerErrorsAndMissingCursorFail() {
        DtkClient api = mock(DtkClient.class);
        when(api.execute(any(DtkGetOrderDetailsRequest.class))).thenReturn(DtkApiResponse.buildFail());
        DtkOrderPageClient client = new DtkOrderPageClient(api, ZoneOffset.UTC);
        assertThrows(IllegalStateException.class, () -> client.fetchTbk(WINDOW, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> client.fetchTbk(WINDOW, 1, 2, null));
    }

    @Test public void jdRefusesKnownClientThatLogsAuthorizationKeys() {
        AbstractDtkApiClient api = mock(AbstractDtkApiClient.class);
        DtkOrderPageClient client = new DtkOrderPageClient(api, ZoneOffset.UTC);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> client.fetchJd(WINDOW, 1, "test-only-authorization"));
        assertTrue(failure.getMessage().contains("line 14"));
        verifyNoInteractions(api);
    }
}
