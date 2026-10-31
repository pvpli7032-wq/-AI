package com.ruoyi.system.cps.withdraw;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.WithdrawalMapper;

/** Durable simulation only. Registration requires an explicit dev/test profile and excludes production. */
@Component
@Conditional(MockWithdrawalProfileCondition.class)
public class MockWithdrawChannel implements WithdrawChannel {
    private final WithdrawalMapper mapper;
    public MockWithdrawChannel(WithdrawalMapper mapper) { this.mapper = mapper; }

    @Override public PaymentResult submit(PaymentRequest request) {
        if (request == null || request.withdrawNo() == null || !request.withdrawNo().matches("WD[a-f0-9]{32}"))
            throw new ServiceException("无效模拟提现号");
        if (request.recipientAccount() == null || request.recipientName() == null)
            throw new ServiceException("缺少收款人快照");
        MockWithdrawalPayment payment = new MockWithdrawalPayment();
        payment.setWithdrawNo(request.withdrawNo()); payment.setAmount(WithdrawalPolicy.requestedAmount(request.amount()));
        payment.setRecipientHash(WithdrawalService.recipientHash("mock", request.recipientAccount()));
        payment.setChannelNo("mock-" + request.withdrawNo());
        try { payment.setStatus(PaymentStatus.valueOf(mapper.configuration("cps.withdraw.mock.status"))); }
        catch (RuntimeException e) { throw new ServiceException("模拟通道结果未配置"); }
        payment.setCallbackToken(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
        payment.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); payment.setUpdatedAt(payment.getCreatedAt());
        mapper.insertMockPayment(payment);
        MockWithdrawalPayment stored = mapper.mockPayment(request.withdrawNo());
        if (stored.getAmount().compareTo(payment.getAmount()) != 0 || !stored.getRecipientHash().equals(payment.getRecipientHash()))
            throw new ServiceException("同一提现号的金额或收款人不一致");
        return result(stored);
    }

    @Override public PaymentResult query(String withdrawNo) {
        MockWithdrawalPayment payment = mapper.mockPayment(withdrawNo);
        // A crash before submit cannot be repaired by resubmitting an ambiguous number.
        return payment == null ? new PaymentResult(withdrawNo, null, PaymentStatus.UNKNOWN, "模拟通道尚无确切结果") : result(payment);
    }

    @Override public PaymentResult handleCallback(ChannelNotification notification) {
        if (notification == null || !"mock".equals(notification.channel()) || notification.payload() == null
                || notification.payload().length() > 4000) throw new ServiceException("无效模拟通道通知");
        try {
            JSONObject payload = JSON.parseObject(notification.payload());
            MockWithdrawalPayment payment = mapper.mockPayment(payload.getString("withdrawNo"));
            if (payment == null || notification.signature() == null || !MessageDigest.isEqual(
                    payment.getCallbackToken().getBytes(StandardCharsets.UTF_8), notification.signature().getBytes(StandardCharsets.UTF_8)))
                throw new ServiceException("模拟通道通知校验失败");
            if (!payment.getChannelNo().equals(payload.getString("channelNo")) || payload.getBigDecimal("amount") == null
                    || payment.getAmount().compareTo(payload.getBigDecimal("amount")) != 0)
                throw new ServiceException("模拟通道通知金额或单号不匹配");
            return simulate(payment.getWithdrawNo(), PaymentStatus.valueOf(payload.getString("status")));
        } catch (ServiceException e) { throw e; }
        catch (RuntimeException e) { throw new ServiceException("无效模拟通道通知"); }
    }

    /** Available only through the permission-protected simulation endpoint. */
    public PaymentResult simulate(String withdrawNo, PaymentStatus status) {
        if (status == null) throw new ServiceException("缺少模拟结果");
        MockWithdrawalPayment payment = mapper.mockPayment(withdrawNo);
        if (payment == null) throw new ServiceException("模拟打款单不存在");
        if ((payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.FAILED)
                && payment.getStatus() != status) throw new ServiceException("模拟通道终态冲突");
        mapper.updateMockPayment(withdrawNo, status, LocalDateTime.now(ZoneOffset.UTC));
        return result(mapper.mockPayment(withdrawNo));
    }

    private static PaymentResult result(MockWithdrawalPayment payment) {
        return new PaymentResult(payment.getWithdrawNo(), payment.getChannelNo(), payment.getStatus(), "模拟结果");
    }
}
