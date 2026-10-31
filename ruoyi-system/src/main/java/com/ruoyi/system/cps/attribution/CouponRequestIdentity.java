package com.ruoyi.system.cps.attribution;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Arrays;
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
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return "local";
        var request = attrs.getRequest();
        String peer = request.getRemoteAddr();
        if (peer == null || peer.isBlank()) return "unknown";
        // Only the deployment's reverse proxy may supply X-Real-IP; never use X-Forwarded-For.
        String proxies = System.getenv().getOrDefault("CPS_TRUSTED_PROXY_IPS", "127.0.0.1,::1,0:0:0:0:0:0:0:1");
        if (Arrays.stream(proxies.split(",")).map(String::trim).noneMatch(peer::equals)) return peer;
        String real = request.getHeader("X-Real-IP");
        return validIp(real) ? real.trim() : peer;
    }
    private static boolean validIp(String value) {
        if (value == null) return false;
        String ip = value.trim();
        if (ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255) return false;
            return true;
        }
        if (!ip.contains(":") || !ip.matches("[0-9a-fA-F:.]+")) return false;
        try { return InetAddress.getByName(ip) instanceof Inet6Address; }
        catch (Exception invalid) { return false; }
    }
}
