package com.ruoyi.system.cps.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.cps.withdraw.*;

/** Withdrawal persistence. Account access is read-only; money is posted through LedgerService. */
public interface WithdrawalMapper {
    Integer lockAdmission();
    WithdrawalAccount lockAccount(@Param("userId") long userId);
    WithdrawalRecord findByRequest(@Param("userId") long userId, @Param("requestKey") String requestKey);
    WithdrawalRecord findByNo(@Param("withdrawNo") String withdrawNo);
    WithdrawalRecord lockWithdrawal(@Param("withdrawNo") String withdrawNo);
    int insertWithdrawal(WithdrawalRecord row);
    int updateWithdrawal(WithdrawalRecord row);
    String selectPolicy();
    String configuration(@Param("key") String key);
    int savePolicy(@Param("config") String config, @Param("operatorId") long operatorId, @Param("now") LocalDateTime now);
    WithdrawalRecipient recipient(@Param("userId") long userId, @Param("channel") String channel);
    int saveRecipient(WithdrawalRecipient recipient);
    String userGroup(@Param("userId") long userId);
    int saveGroup(@Param("userId") long userId, @Param("group") String group,
        @Param("operatorId") long operatorId, @Param("now") LocalDateTime now);
    long countRequests(@Param("userId") long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    long countSuccess(@Param("userId") long userId);
    long countRecipientRequests(@Param("hash") String hash, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    BigDecimal userAmount(@Param("userId") long userId, @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
    BigDecimal channelAmount(@Param("channel") String channel, @Param("userId") Long userId,
        @Param("from") LocalDateTime from, @Param("to") LocalDateTime to, @Param("submitted") boolean submitted);
    long receivedOrders(@Param("userId") long userId);
    long disputeOrders(@Param("userId") long userId);
    long settledOrders(@Param("userId") long userId);
    BigDecimal firstCommissionRatio(@Param("userId") long userId);
    List<WithdrawalRecord> list(@Param("userId") Long userId, @Param("status") String status,
        @Param("offset") long offset, @Param("limit") int limit);
    long count(@Param("userId") Long userId, @Param("status") String status);
    List<String> pending(@Param("afterId") long afterId, @Param("limit") int limit);
    int audit(@Param("withdrawNo") String withdrawNo, @Param("userId") long userId,
        @Param("action") String action, @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus,
        @Param("operatorId") Long operatorId, @Param("reason") String reason, @Param("now") LocalDateTime now);
    MockWithdrawalPayment mockPayment(@Param("withdrawNo") String withdrawNo);
    int insertMockPayment(MockWithdrawalPayment payment);
    int updateMockPayment(@Param("withdrawNo") String withdrawNo, @Param("status") PaymentStatus status,
        @Param("now") LocalDateTime now);
}
