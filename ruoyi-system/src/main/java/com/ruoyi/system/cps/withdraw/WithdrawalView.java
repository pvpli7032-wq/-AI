package com.ruoyi.system.cps.withdraw;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The client never receives full payee identifiers, callback credentials or internal evidence. */
public record WithdrawalView(String withdrawNo, BigDecimal amount, BigDecimal fee,
    WithdrawalStatus status, String channel, String recipient, LocalDateTime createdAt,
    LocalDateTime updatedAt, String message, String statusText, boolean fundsReturned) {
    /** Preserve the original constructor while adding the same display contract to both miniapp paths. */
    public WithdrawalView(String withdrawNo, BigDecimal amount, BigDecimal fee,
            WithdrawalStatus status, String channel, String recipient, LocalDateTime createdAt,
            LocalDateTime updatedAt, String message) {
        this(withdrawNo, amount, fee, status, channel, recipient, createdAt, updatedAt, message,
            status.miniappLabel(), status.fundsReturned());
    }

    public static WithdrawalView of(WithdrawalRecord row) {
        return new WithdrawalView(row.getWithdrawNo(), row.getAmount(), row.getFee(), row.getStatus(),
            row.getChannel(), mask(row.getRecipientAccount()), row.getCreatedAt(), row.getUpdatedAt(),
            switch (row.getStatus()) {
                case MANUAL_REVIEW -> "等待人工审核";
                case APPROVED -> "已审核，尚未打款";
                case SUCCEEDED -> "打款成功";
                case FAILED -> "打款失败，金额已退回余额";
                case REJECTED -> "审核驳回，金额已退回余额";
                case AWAITING_CONFIRMATION -> "等待收款人确认";
                case TIMED_OUT -> "确认超时，正在核实资金结果";
                default -> "正在查询打款结果，请勿重复申请";
            });
    }

    public static String mask(String account) {
        if (account == null || account.length() < 5) return "****";
        return "****" + account.substring(account.length() - 4);
    }
}
