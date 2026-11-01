package com.ruoyi.system.cps.attribution;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.jdbc.core.JdbcTemplate;

final class AttributionData {
    private AttributionData() {}
    static Instant now(JdbcTemplate jdbc) {
        // SQL UTC wall time must not be interpreted in the JDBC connection zone.
        // Business DATETIME columns, like existing orders, are written via Timestamp.
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", (r,n) -> r.getObject(1,LocalDateTime.class)).toInstant(ZoneOffset.UTC);
    }
    static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    static Instant instant(Object value) {
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof Instant instant) return instant;
        throw new IllegalArgumentException("Unsupported database timestamp");
    }
    static void requireUser(JdbcTemplate jdbc, Long userId) {
        if (userId == null || userId <= 0 || jdbc.queryForObject("SELECT COUNT(*) FROM sys_user u WHERE u.user_id=? AND u.status='0' AND u.del_flag='0' AND EXISTS(SELECT 1 FROM sys_wechat_user w WHERE w.user_id=u.user_id)", Integer.class, userId) != 1)
            throw new ServiceException("用户不存在或不可用");
    }
    static boolean validUser(JdbcTemplate jdbc, Long userId) {
        try { requireUser(jdbc, userId); return true; } catch (ServiceException unavailable) { return false; }
    }
    static String text(String value, int length, String name) {
        if (value == null || value.isBlank() || value.length() > length || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl))
            throw new ServiceException(name + "格式不正确");
        return value;
    }
    static String mask(String value) {
        if (value == null || value.isEmpty()) return "";
        if (value.length() <= 4) return "****";
        return "****" + value.substring(value.length() - 4);
    }
    static <T> T first(List<T> values) { return values.isEmpty() ? null : values.get(0); }
}
