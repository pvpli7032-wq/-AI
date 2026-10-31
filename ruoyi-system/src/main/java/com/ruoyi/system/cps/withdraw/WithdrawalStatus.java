package com.ruoyi.system.cps.withdraw;

import java.util.Arrays;
import java.util.List;

/** Approval never means money has been paid. Ambiguous outcomes keep the reservation. */
public enum WithdrawalStatus {
    MANUAL_REVIEW("待人工审核", "审核中"),
    APPROVED("已审核", "打款中"),
    SUBMITTING("提交打款中", "打款中"),
    PROCESSING("通道处理中", "打款中"),
    UNKNOWN("结果未知", "打款中"),
    AWAITING_CONFIRMATION("待收款确认", "打款中"),
    TIMED_OUT("确认超时", "打款中"),
    SUCCEEDED("已打款", "已打款"),
    FAILED("打款失败", "打款失败"),
    REJECTED("审核驳回", "审核驳回");

    private final String label;
    private final String miniappLabel;

    WithdrawalStatus(String label, String miniappLabel) {
        this.label = label;
        this.miniappLabel = miniappLabel;
    }

    /** Raw values remain distinct for administration; only the miniapp display groups pending outcomes. */
    public record Option(String value, String label, String miniappLabel, boolean fundsReturned) {}

    private static final List<Option> OPTIONS = Arrays.stream(values())
        .map(status -> new Option(status.name(), status.label, status.miniappLabel, status.fundsReturned())).toList();

    public static List<Option> options() { return OPTIONS; }
    public String miniappLabel() { return miniappLabel; }
    public boolean fundsReturned() { return this == FAILED || this == REJECTED; }

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == REJECTED;
    }

    public boolean submitted() {
        return this == SUBMITTING || this == PROCESSING || this == UNKNOWN
            || this == AWAITING_CONFIRMATION || this == TIMED_OUT;
    }
}
