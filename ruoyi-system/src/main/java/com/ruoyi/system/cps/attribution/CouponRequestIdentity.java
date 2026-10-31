package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.core.domain.model.LoginUser;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public final class CouponRequestIdentity {
    private CouponRequestIdentity() {}
    public static Long optionalUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof LoginUser login) return login.getUserId();
        return null;
    }
    public static Long requireUserId() {
        Long id = optionalUserId();
        if (id == null || id <= 0) throw new ServiceException("请先登录", 401);
        return id;
    }
    public static String remoteAddress() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
            ? attrs.getRequest().getRemoteAddr() : "local";
    }
}
