-- First payment-time invitation decision, never a cache of the current user relationship.
-- paid_at is canonical Instant text so equality preserves all upstream nanoseconds;
-- DATETIME(6) alone would silently truncate trusted evidence. NULL means unknown payment time.
-- No FK: legacy commission IDs and normalized order-line IDs are supplied by their callers.
CREATE TABLE cps_payment_invitation_snapshot (
    order_line_id BIGINT NOT NULL,
    buyer_user_id BIGINT NOT NULL,
    platform VARCHAR(16) NOT NULL,
    order_no VARCHAR(128) NOT NULL,
    sub_order_no VARCHAR(128) NULL,
    paid_at VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    inviter_user_id BIGINT NULL,
    evidence_id VARCHAR(255) NULL,
    review_status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_line_id),
    CONSTRAINT chk_payment_invitation_review CHECK (review_status IN ('VERIFIED', 'MANUAL_REVIEW')),
    CONSTRAINT chk_payment_invitation_self CHECK (inviter_user_id IS NULL OR inviter_user_id <> buyer_user_id),
    CONSTRAINT chk_payment_invitation_evidence CHECK
        ((review_status = 'VERIFIED' AND paid_at IS NOT NULL AND evidence_id IS NOT NULL)
         OR (review_status = 'MANUAL_REVIEW' AND inviter_user_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Immutable first payment invitation decision; manual review is not automatic attribution';
