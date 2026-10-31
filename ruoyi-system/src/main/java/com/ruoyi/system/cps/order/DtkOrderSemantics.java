package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import com.ruoyi.system.cps.contract.OrderStatus;

/** Versioned DTK query semantics; no SDK enum or undocumented code is used as a substitute. */
public final class DtkOrderSemantics {
    private DtkOrderSemantics() {}
    public static boolean hasMore(Object value) {
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number n) {
            BigDecimal number = new BigDecimal(n.toString());
            if (number.compareTo(BigDecimal.ZERO) == 0) return false;
            if (number.compareTo(BigDecimal.ONE) == 0) return true;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim()) || "1".equals(text.trim())) return true;
            if ("false".equalsIgnoreCase(text.trim()) || "0".equals(text.trim())) return false;
        }
        throw new IllegalStateException("京东hasMore缺失或无法解析，窗口不能完成");
    }
    public static OrderStatus jd(String code, String payMonth, LocalDate today) {
        if (code == null || !code.matches("\\d+")) return null;
        int value;
        try { value = Integer.parseInt(code); } catch (NumberFormatException unknown) { return null; }
        if (value >= 2 && value <= 14) return OrderStatus.INVALID;
        if (value != 16 && value != 17) return null; // 15 unpaid; 18/19..28 explicitly unresolved.
        if (pastPayDate(payMonth, today)) return OrderStatus.SETTLED;
        return value == 16 ? OrderStatus.PAID : OrderStatus.RECEIVED;
    }
    public static boolean pastPayDate(String value, LocalDate today) {
        if (value == null || value.isBlank() || "0".equals(value.trim())) return false;
        String text = value.trim();
        LocalDate date;
        // Month-only values cannot prove settlement until the entire stated month has passed.
        if (text.matches("\\d{6}")) date = YearMonth.parse(text, DateTimeFormatter.ofPattern("uuuuMM")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT)).atEndOfMonth();
        else if (text.matches("\\d{8}")) date = LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
        else date = LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        return date.isBefore(today);
    }
    public static OrderStatus pdd(String code) {
        if (code == null) return null;
        return switch (code) {
            case "0", "1" -> OrderStatus.PAID;
            case "2", "3" -> OrderStatus.RECEIVED;
            case "4", "8" -> OrderStatus.INVALID;
            case "5" -> OrderStatus.SETTLED;
            default -> null; // -1 unpaid, unknown statuses are never treated as paid.
        };
    }
    public static OrderStatus tbkRefund(String code, BigDecimal refund, BigDecimal finished) {
        if (code == null) return null;
        return switch (code) {
            case "4", "11", "12" -> OrderStatus.DISPUTED;
            case "2", "13" -> {
                if (refund == null || finished == null || refund.signum() <= 0 || finished.signum() <= 0
                    || refund.compareTo(finished) > 0) throw new IllegalStateException("退款金额或结算金额无效");
                yield refund.compareTo(finished) == 0 ? OrderStatus.FULL_REFUND : OrderStatus.PARTIAL_REFUND;
            }
            default -> null; // 3 restores persisted previous state. 14/15 remain evidence only.
        };
    }
}
