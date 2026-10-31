package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;

/** Current projection is disposable; immutable revisions and the instruction outbox are the audit trail. */
public interface RewardRepricingStore {
    record Basis(String awardId, BigDecimal entitlement, BigDecimal refundRecovered, String revisionKey) {}
    Basis basis(String awardId);
    void revise(Basis previous, Basis next, BigDecimal originalGrant, String evidence);
    void recover(String awardId, BigDecimal cumulative);
}
