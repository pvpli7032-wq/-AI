package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.InviteBindingMapper;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.mapper.SysWechatUserMapper;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** First authorization fixes the single direct inviter. Later changes require an audited admin action. */
@Service
public class InviteBindingService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger log = LoggerFactory.getLogger(InviteBindingService.class);
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
        lockInvitationMutex();
        lockActiveUser(user.getUserId());
        // Lock the system-user row even before a wechat row exists, serializing concurrent registrations.
        if (invitations.selectByUserId(user.getUserId()) != null || users.selectByOpenid(user.getOpenid()) != null
                || invitations.countHistoricalOrders(user.getUserId()) != 0) {
            throw new ServiceException("仅新用户首次授权时可以绑定邀请人");
        }
        Long inviter = null;
        if (invitationCode != null && !invitationCode.isBlank()) {
            String code = invitationCode.trim();
            SysWechatUser owner = isNumericCode(code) ? invitations.findInviter(code) : null;
            if (owner == null || Objects.equals(owner.getUserId(), user.getUserId())) {
                log.warn("首次登录邀请码无效或邀请人不可用，用户 {} 未绑定上级", user.getUserId());
            } else {
                inviter = owner.getUserId();
            }
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
        lockInvitationMutex();
        lockActiveUser(userId);
        // Login may already own a REPEATABLE_READ snapshot. The system-user lock serializes writers;
        // this current read must observe the code committed by an earlier concurrent login.
        SysWechatUser user = invitations.selectLockedUser(userId);
        if (user == null) throw new ServiceException("微信用户不存在");
        if (isNumericCode(user.getInvitationCode())) return user.getInvitationCode();
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
        lockInvitationMutex();
        lockActiveUser(userId);
        SysWechatUser user = invitations.selectByUserId(userId);
        if (user == null) throw new ServiceException("微信用户不存在");
        if (inviterUserId != null) {
            SysWechatUser owner = invitations.selectByUserId(inviterUserId);
            if (owner == null || owner.getInvitationCode() == null
                    || invitations.findInviter(owner.getInvitationCode()) == null) {
                throw new ServiceException("邀请人不存在或不可用");
            }
            Set<Long> visited = new HashSet<>();
            Long cursor = inviterUserId;
            while (cursor != null) {
                if (Objects.equals(cursor, userId) || !visited.add(cursor)) {
                    throw new ServiceException("邀请关系不能形成环");
                }
                SysWechatUser ancestor = invitations.selectByUserId(cursor);
                cursor = ancestor == null ? null : ancestor.getInviterUserId();
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

    private void lockInvitationMutex() {
        if (invitations.lockInvitationMutex() == null) throw new ServiceException("邀请码分配不可用");
    }

    private static boolean isNumericCode(String code) {
        return code != null && code.matches("[1-9][0-9]{3,63}");
    }

    /** Called under the global mutex; a random starting point is scanned until a free code is found. */
    private String newCode() {
        BigInteger ten = BigInteger.TEN;
        for (int length = 4; length <= 64; length++) {
            BigInteger lower = ten.pow(length - 1);
            BigInteger capacity = lower.multiply(BigInteger.valueOf(9));
            if (BigInteger.valueOf(invitations.countCodesOfLength(length)).compareTo(capacity) >= 0) continue;
            BigInteger offset;
            do { offset = new BigInteger(capacity.bitLength(), RANDOM); } while (offset.compareTo(capacity) >= 0);
            BigInteger start = offset;
            do {
                String code = lower.add(offset).toString();
                if (invitations.countCode(code) == 0) return code;
                offset = offset.add(BigInteger.ONE).mod(capacity);
            } while (!offset.equals(start));
        }
        throw new ServiceException("邀请码空间已用完");
    }
}
