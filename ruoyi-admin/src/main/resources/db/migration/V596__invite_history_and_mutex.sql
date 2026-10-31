-- Serializes invitation-code allocation and administrative graph changes across nodes.
CREATE TABLE cps_invitation_mutex (
    id TINYINT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO cps_invitation_mutex(id) VALUES (1);

-- The legacy binding is the earliest known state, not proof of when it was set.
-- Preserve the current binding only for users without any recorded history.
INSERT INTO cps_invite_binding_audit
    (user_id, previous_inviter_user_id, next_inviter_user_id, operator_id, event_type, reason, created_at)
SELECT w.user_id, NULL, w.inviter_user_id, w.user_id, 'LEGACY_BACKFILL',
       '迁移时已存在的邀请关系，原始绑定时间未知', '1970-01-01 00:00:01.000'
FROM sys_wechat_user w
WHERE w.inviter_user_id IS NOT NULL AND w.inviter_user_id <> w.user_id
  AND NOT EXISTS (SELECT 1 FROM cps_invite_binding_audit a WHERE a.user_id = w.user_id);
