package com.ruoyi.system.cps.mapper;

import com.ruoyi.system.domain.SysWechatUser;
import java.time.Instant;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;

/** Invitation writes are separate from profile updates and never touch order snapshots. */
public interface InviteBindingMapper {
    Long lockSystemUser(@Param("userId") Long userId);
    SysWechatUser selectByUserId(@Param("userId") Long userId);
    SysWechatUser selectLockedUser(@Param("userId") Long userId);
    SysWechatUser findInviter(@Param("code") String code);
    long countHistoricalOrders(@Param("userId") Long userId);
    int setInvitationCode(@Param("userId") Long userId, @Param("code") String code);
    int changeInviter(@Param("userId") Long userId, @Param("inviterUserId") Long inviterUserId);
    Long resolveAt(@Param("userId") Long userId, @Param("paidAt") Instant paidAt);
    LocalDateTime databaseUtcNow();
    int appendAudit(@Param("userId") Long userId, @Param("previousInviter") Long previousInviter,
                    @Param("nextInviter") Long nextInviter, @Param("operatorId") Long operatorId,
                    @Param("eventType") String eventType, @Param("reason") String reason,
                    @Param("createdAt") Instant createdAt);
}
