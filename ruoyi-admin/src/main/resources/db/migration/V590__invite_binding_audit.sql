-- Only append invitation changes. Order and settlement snapshots are never rewritten here.
CREATE TABLE cps_invite_binding_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    previous_inviter_user_id BIGINT NULL,
    next_inviter_user_id BIGINT NULL,
    operator_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_invite_audit_user (user_id, created_at),
    CONSTRAINT chk_invite_no_self CHECK (next_inviter_user_id IS NULL OR next_inviter_user_id <> user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
