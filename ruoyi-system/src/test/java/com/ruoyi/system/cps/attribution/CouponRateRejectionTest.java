package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.*;

public class CouponRateRejectionTest {
    @Test public void rejectedRequestIsNotPersistedOrSentUpstream() {
        var limiter = mock(CouponRateLimiter.class);
        var logs = mock(CouponQueryLogger.class);
        var adapter = mock(UnionAdapter.class);
        var env = new MockEnvironment();
        var service = new CouponConversionService(List.of(adapter), new UnionAdapterConfiguration(env),
            new CouponSettings(mock(ISysConfigService.class), env), limiter, mock(CouponQuoteCache.class),
            logs, mock(PidPoolService.class), mock(AttributionTokenService.class));
        doThrow(new UnionCouponException("RATE_LIMITED", "limited"))
            .when(limiter).check(1L, "address", Platform.TBK);
        var result = service.convert(1L, "address", Platform.TBK, "123");
        assertEquals("RATE_LIMITED", result.result());
        verifyNoInteractions(logs, adapter);
        verify(limiter, never()).failed(any(), any());
    }
}
