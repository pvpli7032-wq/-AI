package com.ruoyi.system.cps.withdraw;

/** Approval never means money has been paid. Ambiguous outcomes keep the reservation. */
public enum WithdrawalStatus {
    MANUAL_REVIEW, APPROVED, SUBMITTING, PROCESSING, UNKNOWN,
    AWAITING_CONFIRMATION, TIMED_OUT, SUCCEEDED, FAILED, REJECTED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED;
    }

    public boolean submitted() {
        return this == SUBMITTING || this == PROCESSING || this == UNKNOWN
            || this == AWAITING_CONFIRMATION || this == TIMED_OUT;
    }
}
