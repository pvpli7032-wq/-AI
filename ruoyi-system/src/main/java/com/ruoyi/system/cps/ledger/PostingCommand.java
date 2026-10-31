package com.ruoyi.system.cps.ledger;

import java.math.BigDecimal;
/**
 * One posting appends one flow row; account must equal debitAccount.
 * businessType, businessId and account populate business_type, business_id and account;
 * businessKey populates idempotency_key. businessId identifies each posting: recoveries
 * include the eventKey or a sequence, so a second partial recovery is distinct.
 * Withdrawal application, payment success and payment failure use distinct businessType values.
 * When multiple postings of the same flow type relate to one order, preserve the V100
 * legacy key by leaving order_commission_id null and storing the order reference in related_order_id.
 */
public record PostingCommand(String businessKey, String businessType, String businessId, AccountType account,
    Long userId, Long orderLineId, String type, AccountType debitAccount, AccountType creditAccount,
    BigDecimal amount, String reason, Long operatorId) {}
