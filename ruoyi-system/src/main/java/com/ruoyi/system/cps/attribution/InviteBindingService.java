package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.InviteBindingMapper;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.mapper.SysWechatUserMapper;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First authorization fixes the single direct inviter. Later changes require an audited admin action. */
@Service
public class InviteBindingService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final InviteBindingMapper invitations;
    private final SysWechatUserMapper users;

    public InviteBindingService(InviteBindingMapper invitations, SysWechatUserMapper users) {
        this.invitations = invitations;
        this.users = users;
    }

    @Transactional(rollbackFor = Exception.class)
    public int registerFirstAuthorization(SysWechatUser user, String invitationCode) {
        if (user == null || user.getUserId() == null || user.getOpenid() == null || user.getOpenid().isBlank()) {
            throw new ServiceException("首次授权用户信息不完整");
        }
        lockActiveUser(user.getUserId());
        // Lock the system-user row even before a wechat row exists, serializing concurrent registrations.
        if (invitations.selectByUserId(user.getUserId()) != null || users.selectByOpenid(user.getOpenid()) != null
                || invitations.countHistoricalOrders(user.getUserId()) != 0) {
            throw new ServiceException("仅新用户首次授权时可以绑定邀请人");
        }
        Long inviter = null;
        if (invitationCode != null && !invitationCode.isBlank()) {
            String code = invitationCode.trim();
            if (code.length() > 64) throw new ServiceException("邀请码无效");
            SysWechatUser owner = invitations.findInviter(code);
            if (owner == null) throw new ServiceException("邀请码无效或邀请人不可用");
            inviter = owner.getUserId();
            if (Objects.equals(inviter, user.getUserId())) throw new ServiceException("不能邀请自己");
        }
        // Never accept caller-supplied inviter IDs or caller-chosen own codes.
        user.setInvitationCode(newCode());
        user.setInviterUserId(inviter);
        user.setCreateTime(new Date());
        int inserted = users.insertSysWechatUser(user);
        if (inserted != 1) throw new ServiceException("首次授权保存失败");
        if (invitations.appendAudit(user.getUserId(), null, inviter, user.getUserId(), "FIRST_AUTH", "首次授权", now()) != 1) {
            throw new ServiceException("邀请绑定审计保存失败");
        }
        return inserted;
    }

    @Transactional(rollbackFor = Exception.class)
    public String ensureInvitationCode(Long userId) {
        lockActiveUser(userId);
        // Login may already own a REPEATABLE_READ snapshot. The system-user lock serializes writers;
        // this current read must observe the code committed by an earlier concurrent login.
        SysWechatUser user = invitations.selectLockedUser(userId);
        if (user == null) throw new ServiceException("微信用户不存在");
        if (user.getInvitationCode() != null && !user.getInvitationCode().isBlank()) return user.getInvitationCode();
        String code = newCode();
        if (invitations.setInvitationCode(userId, code) != 1) throw new ServiceException("邀请码生成失败");
        return code;
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeInviter(Long userId, Long inviterUserId, Long operatorId, String reason) {
        if (operatorId == null || operatorId <= 0 || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new ServiceException("改绑必须填写操作人和原因（不超过500字）");
        }
        if (Objects.equals(userId, inviterUserId)) throw new ServiceException("不能邀请自己");
        lockActiveUser(userId);
        SysWechatUser user = invitations.selectByUserId(userId);
        if (user == null) throw new ServiceException("微信用户不存在");
        if (inviterUserId != null) {
            SysWechatUser owner = invitations.selectByUserId(inviterUserId);
            if (owner == null || owner.getInvitationCode() == null
                    || invitations.findInviter(owner.getInvitationCode()) == null) {
                throw new ServiceException("邀请人不存在或不可用");
            }
        }
        if (Objects.equals(user.getInviterUserId(), inviterUserId)) return;
        if (invitations.changeInviter(userId, inviterUserId) != 1) throw new ServiceException("改绑失败");
        if (invitations.appendAudit(userId, user.getInviterUserId(), inviterUserId, operatorId, "ADMIN_CHANGE", reason.trim(), now()) != 1) {
            throw new ServiceException("邀请改绑审计保存失败");
        }
        // Existing orders and settlement snapshots deliberately have no write path here.
    }

    private void lockActiveUser(Long userId) {
        if (userId == null || userId <= 0 || invitations.lockSystemUser(userId) == null) {
            throw new ServiceException("用户不存在或不可用");
        }
    }

    /** Resolve the inviter at payment time; absent historical evidence never falls back to today's binding. */
    public Long resolveAt(Long userId, Instant paidAt) {
        if (userId == null || paidAt == null) return null;
        return invitations.resolveAt(userId, paidAt);
    }

    private Instant now() {
        // Read UTC as wall time, then bind its Instant exactly like an order's paid_time.
        // Direct SQL UTC_TIMESTAMP writes would not round-trip through a GMT+8 JDBC connection.
        return invitations.databaseUtcNow().toInstant(ZoneOffset.UTC);
    }

    public static String newCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
