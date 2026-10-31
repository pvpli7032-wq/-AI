package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionCouponException;
import com.ruoyi.union.dtk.api.client.DtkClient;
import com.ruoyi.union.dtk.api.request.search.DtkJdUnionQueryRequest;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.search.DtkJdUnionQueryResponse;
import com.ruoyi.union.dtk.config.DtkConfig;

public class CouponDtkGatewayTest {
    @Test public void nextCallAppliesBackendLimitsWithoutCancellingEarlierRequest() throws Exception {
        Map<String, String> values = new ConcurrentHashMap<>();
        values.put("cps.coupon.union-parallelism", "1");
        values.put("cps.coupon.union-queue-capacity", "1");
        values.put("cps.coupon.union-timeout-ms", "5000");
        ISysConfigService config = mock(ISysConfigService.class);
        when(config.selectConfigByKey(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
        DtkClient transport = mock(DtkClient.class);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondInterrupted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        DtkJdUnionQueryRequest firstRequest = new DtkJdUnionQueryRequest();
        DtkJdUnionQueryRequest secondRequest = new DtkJdUnionQueryRequest();
        when(transport.execute(same(firstRequest))).thenAnswer(call -> {
            firstEntered.countDown();
            assertTrue(releaseFirst.await(4, TimeUnit.SECONDS));
            return DtkApiResponse.buildSuccess(new DtkJdUnionQueryResponse());
        });
        when(transport.execute(same(secondRequest))).thenAnswer(call -> {
            secondEntered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) {
                secondInterrupted.countDown(); Thread.currentThread().interrupt();
            }
            return DtkApiResponse.buildSuccess(new DtkJdUnionQueryResponse());
        });
        CouponDtkGateway gateway = new CouponDtkGateway(new DtkConfig(),
                new CouponSettings(config, new MockEnvironment()), mock(PidPoolService.class), (key, secret) -> transport);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<DtkApiResponse<DtkJdUnionQueryResponse>> first = caller.submit(() -> gateway.execute(Platform.JD, firstRequest));
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            values.put("cps.coupon.union-parallelism", "2");
            values.put("cps.coupon.union-timeout-ms", "100");
            UnionCouponException timeout = assertThrows(UnionCouponException.class,
                    () -> gateway.execute(Platform.JD, secondRequest));
            assertEquals("UPSTREAM_TIMEOUT", timeout.code());
            assertTrue("Increased parallelism must start the second request while first is active",
                    secondEntered.await(2, TimeUnit.SECONDS));
            assertTrue(secondInterrupted.await(2, TimeUnit.SECONDS));
            assertFalse("Updated timeout must not shorten the earlier request", first.isDone());
            releaseFirst.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).isSuccess());
            gateway.close();
            UnionCouponException closed = assertThrows(UnionCouponException.class,
                    () -> gateway.execute(Platform.JD, new DtkJdUnionQueryRequest()));
            assertEquals("UPSTREAM_BUSY", closed.code());
            verify(transport).execute(same(firstRequest));
            verify(transport).execute(same(secondRequest));
            verifyNoMoreInteractions(transport);
        } finally { releaseFirst.countDown(); gateway.close(); caller.shutdownNow(); }
    }
}
