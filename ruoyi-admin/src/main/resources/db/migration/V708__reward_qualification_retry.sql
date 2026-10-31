-- Reward qualification has its own durable schedule; AVAILABLE rebate rows are
-- not evidence that payment-ordered rewards have qualified or been excluded.
ALTER TABLE cps_settlement_position
    ADD COLUMN reward_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reward_retry_at DATETIME(3) NULL,
    ADD KEY idx_settlement_reward_retry (reward_pending, reward_retry_at, order_line_id);

-- Recover already released/deferred candidates without modifying payment-time
-- snapshots, award ordinals, ledger entries or reward state. Historical-unknown
-- candidates remain manual and are not silently excluded from lifetime history.
UPDATE cps_settlement_position p
JOIN cps_reward_order_state r ON r.order_id=p.order_line_id
SET p.reward_pending=TRUE, p.reward_retry_at=CURRENT_TIMESTAMP(3)
WHERE p.terms_json IS NOT NULL AND p.referral_manual=FALSE
  AND r.qualified=FALSE AND r.closed=FALSE;
