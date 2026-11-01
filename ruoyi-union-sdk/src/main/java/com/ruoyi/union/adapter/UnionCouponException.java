package com.ruoyi.union.adapter;

/** Stable, non-sensitive error codes at the coupon alliance boundary. */
public final class UnionCouponException extends RuntimeException {
    private final String code;

    public UnionCouponException(String code, String message) {
        super(message);
        this.code = code;
    }

    public UnionCouponException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() { return code; }
    public String getCode() { return code; }
}
