-- Immutable original awards and payment snapshots are deliberately untouched.
-- A formerly zero guarantee may acquire an entitlement later: preserve a zero original-grant anchor.
ALTER TABLE cps_reward_award DROP CHECK chk_reward_award_amount;
ALTER TABLE cps_reward_award ADD CONSTRAINT chk_reward_award_amount
    CHECK (amount >= 0 AND recovered >= 0 AND recovered <= amount);
CREATE TABLE cps_reward_basis_revision (
    revision_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    award_id VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    original_grant DECIMAL(18,2) NOT NULL,
    previous_entitlement DECIMAL(18,2) NOT NULL,
    entitlement DECIMAL(18,2) NOT NULL,
    evidence JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_reward_revision_award (award_id),
    CHECK (original_grant >= 0 AND previous_entitlement >= 0 AND entitlement >= 0)
) ENGINE=InnoDB;
CREATE TABLE cps_reward_entitlement (
    award_id VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    entitlement DECIMAL(18,2) NOT NULL,
    refund_recovered DECIMAL(18,2) NOT NULL DEFAULT 0,
    revision_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CHECK (entitlement >= 0 AND refund_recovered >= 0 AND refund_recovered <= entitlement)
) ENGINE=InnoDB;
