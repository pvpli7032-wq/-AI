package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.assertEquals;
import org.junit.After;
import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class CouponRequestIdentityTest {
    @After public void clear() { RequestContextHolder.resetRequestAttributes(); }
    @Test public void untrustedPeerCannotForgeProxyHeaders() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.4");
        request.addHeader("X-Real-IP", "203.0.113.8");
        request.addHeader("X-Forwarded-For", "203.0.113.9");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertEquals("198.51.100.4", CouponRequestIdentity.remoteAddress());
    }
}
