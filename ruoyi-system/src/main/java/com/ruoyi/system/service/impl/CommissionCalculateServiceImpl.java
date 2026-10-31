package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;
import java.util.Map;
import java.util.Objects;
import com.ruoyi.system.cps.ledger.AccountType;
import com.ruoyi.system.cps.ledger.LedgerPosting;
import com.ruoyi.system.cps.ledger.PostingCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.domain.SysCommissionConfig;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.mapper.SysCommissionConfigMapper;
import com.ruoyi.system.mapper.SysCommissionFlowMapper;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.mapper.SysWechatUserMapper;
import com.ruoyi.system.service.ICommissionCalculateService;
import com.ruoyi.system.service.ISysCommissionConfigService;

/**
 * 佣金计算服务实现
 * 
 * @author ruoyi
 */
@Service
public class CommissionCalculateServiceImpl implements ICommissionCalculateService
{
    @Autowired
    private SysCommissionConfigMapper commissionConfigMapper;

    @Autowired
    private ISysCommissionConfigService commissionConfigService;

    @Autowired
    private SysOrderCommissionMapper orderCommissionMapper;

    @Autowired
    private SysAgentCommissionAccountMapper accountMapper;

    @Autowired
    private SysCommissionFlowMapper flowMapper;

    @Autowired
    private SysWechatUserMapper wechatUserMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate ledgerJdbc;

    /**
     * 计算订单佣金
     */
    @Override
    @Transactional
    public SysOrderCommission calculateCommission(
        String orderId,
        String platformType,
        String itemId,
        String itemTitle,
        BigDecimal orderAmount,
        BigDecimal estimatedCommission,
        BigDecimal commissionRate,
        Long buyerUserId,
        String orderTime)
    {
        if (buyerUserId == null || buyerUserId <= 0 || estimatedCommission == null || estimatedCommission.signum() < 0)
            throw new ServiceException("购买用户和预估佣金无效");
        // 检查订单是否已存在
        SysOrderCommission existingOrder = orderCommissionMapper
            .selectSysOrderCommissionByOrderIdAndPlatformType(orderId, platformType);
        if (existingOrder != null)
        {
            return existingOrder;
        }

        // 创建订单佣金记录
        SysOrderCommission orderCommission = new SysOrderCommission();
        orderCommission.setOrderId(orderId);
        orderCommission.setPlatformType(platformType);
        orderCommission.setItemId(itemId);
        orderCommission.setItemTitle(itemTitle);
        orderCommission.setOrderAmount(orderAmount);
        orderCommission.setEstimatedCommission(estimatedCommission);
        orderCommission.setCommissionRate(commissionRate);
        orderCommission.setBuyerUserId(buyerUserId);
        orderCommission.setAgentUserId(buyerUserId);
        // 将String类型的orderTime转换为Date类型
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            sdf.setLenient(false);
            orderCommission.setOrderTime(sdf.parse(orderTime));
            orderCommission.setPaidTime(orderCommission.getOrderTime());
        } catch (java.text.ParseException | NullPointerException e) {
            throw new ServiceException("缺少有效的付款时间");
        }
        orderCommission.setOrderStatus("pending"); // 待结算
        orderCommission.setSettleStatus("unsettled"); // 未结算
        orderCommission.setCreateTime(new Date());

        // Snapshot the invitation on the order, never consult the retired agent application module.
        // Subsequent settlement/refund must use this snapshot, not the user's current inviter.
        List<Long> inviters = ledgerJdbc.query("SELECT inviter_user_id FROM sys_wechat_user WHERE user_id=? FOR UPDATE",
            (rs, row) -> rs.getObject(1, Long.class), buyerUserId);
        if (inviters.size() != 1) throw new ServiceException("购买用户不存在或邀请关系不唯一");
        Long inviterId = inviters.getFirst();
        if (buyerUserId.equals(inviterId)) throw new ServiceException("不能邀请自己");

        // 获取佣金配置
        BigDecimal selfRate = getSelfCommissionRate();
        BigDecimal promotionRate = getPromotionCommissionRate();

        boolean hasReferrer = inviterId != null;
        
        orderCommission.setHasReferrer(hasReferrer ? "1" : "0");

        if (hasReferrer)
        {
            // An inactive/deleted inviter cannot receive a new commission.
            boolean activeInviter = ledgerJdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'", Long.class, inviterId) == 1;
            if (activeInviter)
            {
                orderCommission.setReferrerUserId(inviterId);
                
                // 计算自购佣金（购买者获得）
                BigDecimal selfCommissionAmount = estimatedCommission
                    .multiply(selfRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                
                // 计算推广佣金（推荐人获得）
                BigDecimal promotionCommissionAmount = selfCommissionAmount
                    .multiply(promotionRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                
                orderCommission.setSelfCommissionRate(selfRate);
                orderCommission.setSelfCommissionAmount(selfCommissionAmount);
                orderCommission.setPromotionCommissionRate(promotionRate);
                orderCommission.setPromotionCommissionAmount(promotionCommissionAmount);
            }
            else
            {
                // 推荐人不存在，按无推荐人处理
                orderCommission.setHasReferrer("0");
                orderCommission.setReferrerUserId(null);
                
                BigDecimal selfCommissionAmount = estimatedCommission
                    .multiply(selfRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                
                orderCommission.setSelfCommissionRate(selfRate);
                orderCommission.setSelfCommissionAmount(selfCommissionAmount);
                orderCommission.setPromotionCommissionRate(BigDecimal.ZERO);
                orderCommission.setPromotionCommissionAmount(BigDecimal.ZERO);
            }
        }
        else
        {
            // 无推荐人：只有自购佣金
            orderCommission.setReferrerUserId(null);
            
            BigDecimal selfCommissionAmount = estimatedCommission
                .multiply(selfRate)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            
            orderCommission.setSelfCommissionRate(selfRate);
            orderCommission.setSelfCommissionAmount(selfCommissionAmount);
            orderCommission.setPromotionCommissionRate(BigDecimal.ZERO);
            orderCommission.setPromotionCommissionAmount(BigDecimal.ZERO);
        }

        // 保存订单佣金记录
        orderCommissionMapper.insertSysOrderCommission(orderCommission);

        return orderCommission;
    }

    /**
     * 订单结算
     */
    @Override
    @Transactional
    public boolean settleOrder(Long orderCommissionId, BigDecimal actualCommission)
    {
        // 先锁订单，串行处理同一订单的结算与退款；随后按用户ID顺序锁账户。
        SysOrderCommission orderCommission = accountMapper
            .selectOrderCommissionForUpdate(orderCommissionId);
        
        if (orderCommission == null)
        {
            throw new RuntimeException("订单佣金记录不存在");
        }

        rejectEventManagedLegacyPosting(orderCommission);
        if (actualCommission == null || actualCommission.signum() < 0)
        {
            throw new ServiceException("实际佣金必须为非负金额");
        }
        if ("refunded".equals(orderCommission.getOrderStatus())
            || "cancelled".equals(orderCommission.getOrderStatus()))
        {
            throw new ServiceException("已退款或取消的订单不能结算");
        }
        if ("settled".equals(orderCommission.getSettleStatus()))
        {
            if (!("confirmed".equals(orderCommission.getOrderStatus())
                || "settled".equals(orderCommission.getOrderStatus())))
            {
                throw new ServiceException("订单状态与结算状态不一致");
            }
            if (orderCommission.getActualCommission() == null
                || orderCommission.getActualCommission().compareTo(actualCommission) != 0)
            {
                throw new ServiceException("同一订单重复结算的金额不一致");
            }
            return true;
        }
        if (!"pending".equals(orderCommission.getOrderStatus()))
        {
            throw new ServiceException("订单当前状态不允许结算");
        }
        lockPostingAccounts(orderCommission);

        // 保留联盟实际佣金原值，仅用户分成金额舍入到分。
        orderCommission.setActualCommission(actualCommission);
        
        // 重新计算实际分成金额（按实际佣金比例分配）
        BigDecimal actualSelfAmount = actualCommission
            .multiply(orderCommission.getSelfCommissionRate())
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        
        BigDecimal actualPromotionAmount = BigDecimal.ZERO;
        if ("1".equals(orderCommission.getHasReferrer()) && orderCommission.getReferrerUserId() != null)
        {
            actualPromotionAmount = actualSelfAmount
                .multiply(orderCommission.getPromotionCommissionRate())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        }

        orderCommission.setSelfCommissionAmount(actualSelfAmount);
        orderCommission.setPromotionCommissionAmount(actualPromotionAmount);
        orderCommission.setOrderStatus("confirmed");
        orderCommission.setSettleStatus("settled");
        orderCommission.setSettleTime(DateUtils.getNowDate());
        orderCommission.setUpdateTime(new Date());
        
        if (accountMapper.updateOrderSettlement(orderCommission) != 1)
        {
            throw new ServiceException("订单结算状态保存失败");
        }

        // 更新购买者的佣金账户（自购佣金）
        updateUserAccount(orderCommission.getBuyerUserId(), actualSelfAmount, 
            "income_self", orderCommission.getId(), "自购佣金入账");

        // 如果有推荐人，更新推荐人的佣金账户（推广佣金）
        if ("1".equals(orderCommission.getHasReferrer()) && orderCommission.getReferrerUserId() != null)
        {
            updateUserAccount(orderCommission.getReferrerUserId(), actualPromotionAmount, 
                "income_promotion", orderCommission.getId(), "推广佣金入账");
        }

        return true;
    }

    /**
     * 订单退款处理
     */
    @Override
    @Transactional
    public boolean refundOrder(Long orderCommissionId)
    {
        // 先锁订单，串行处理同一订单的结算与退款；随后按用户ID顺序锁账户。
        SysOrderCommission orderCommission = accountMapper
            .selectOrderCommissionForUpdate(orderCommissionId);
        
        if (orderCommission == null)
        {
            throw new RuntimeException("订单佣金记录不存在");
        }

        rejectEventManagedLegacyPosting(orderCommission);
        if ("refunded".equals(orderCommission.getOrderStatus()))
        {
            return true;
        }
        if (!"settled".equals(orderCommission.getSettleStatus())
            || !("confirmed".equals(orderCommission.getOrderStatus())
                || "settled".equals(orderCommission.getOrderStatus())))
        {
            throw new ServiceException("订单未结算或当前状态不允许退款");
        }
        lockPostingAccounts(orderCommission);

        // 自购与推广分别追加退款流水并回退对应统计；余额不足仍记录负余额。
        BigDecimal selfAmount = refundableIncome(orderCommission.getBuyerUserId(), "income_self",
            orderCommissionId, orderCommission.getSelfCommissionAmount());
        if (selfAmount != null && selfAmount.signum() > 0)
        {
            updateUserAccount(orderCommission.getBuyerUserId(), selfAmount.negate(), "refund_self",
                orderCommission.getId(), "订单退款扣减自购佣金");
        }
        if ("1".equals(orderCommission.getHasReferrer()) && orderCommission.getReferrerUserId() != null)
        {
            BigDecimal promotionAmount = refundableIncome(orderCommission.getReferrerUserId(), "income_promotion",
                orderCommissionId, orderCommission.getPromotionCommissionAmount());
            if (promotionAmount != null && promotionAmount.signum() > 0)
            {
                updateUserAccount(orderCommission.getReferrerUserId(), promotionAmount.negate(), "refund_promotion",
                    orderCommission.getId(), "订单退款扣减推广佣金");
            }
        }

        // 更新订单状态
        orderCommission.setOrderStatus("refunded");
        orderCommission.setUpdateTime(new Date());
        if (accountMapper.updateOrderSettlement(orderCommission) != 1)
        {
            throw new ServiceException("订单结算状态保存失败");
        }

        return true;
    }

    /** Legacy admin commands carry neither an alliance event key nor cumulative recovery.
     * Never let them post a second time for orders owned by the event settlement engine. */
    private void rejectEventManagedLegacyPosting(SysOrderCommission order)
    {
        if (order.getLineStatus() != null)
            throw new ServiceException("事件管理订单必须经结算事件处理，旧结算/退款入口不可入账");
    }

    private BigDecimal refundableIncome(Long userId, String type, Long orderId, BigDecimal legacyAmount)
    {
        List<SysCommissionFlow> incomes = flowMapper.selectIncomeForOrder(userId, type, orderId);
        if (incomes.isEmpty())
        {
            // 兼容结算时尚未记录收入流水的历史订单。
            return legacyAmount;
        }
        BigDecimal remaining = BigDecimal.ZERO;
        for (SysCommissionFlow income : incomes)
        {
            if (flowMapper.selectReversalByOriginal(income.getId()) == null)
            {
                remaining = remaining.add(income.getAmount());
            }
        }
        return remaining;
    }

    /**
     * 更新用户佣金账户
     */
    @Override
    @Transactional
    public SysCommissionFlow reverseCommissionFlow(Long originalFlowId, String reason,
        String idempotencyKey, Long operatorId)
    {
        if (originalFlowId == null || originalFlowId <= 0 || operatorId == null || operatorId <= 0
            || reason == null || reason.isBlank() || reason.length() > 500
            || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
        {
            throw new ServiceException("冲正需要有效的原流水、原因、幂等键及操作人");
        }
        // 流水只能追加，普通读取可避免与账户锁产生反向锁序。
        SysCommissionFlow original = flowMapper.selectSysCommissionFlowById(originalFlowId);
        if (original == null || original.getAmount() == null || original.getUserId() == null)
        {
            throw new ServiceException("原流水不存在或缺少必要数据");
        }
        // Contract transfers store a positive transfer amount (unlike signed legacy flows).
        // Never let the legacy reversal endpoint reinterpret a withdrawal reservation as income.
        if (original.getIdempotencyKey() != null)
            throw new ServiceException("带业务幂等键的账本分录不能使用旧冲正入口");
        String type = original.getFlowType();
        if (!("income_self".equals(type) || "income_promotion".equals(type) || "withdraw".equals(type)))
        {
            throw new ServiceException("此类型流水不支持冲正");
        }
        if ("income_self".equals(type) || "income_promotion".equals(type))
        {
            Long orderId = original.getOrderCommissionId();
            if (orderId == null)
            {
                try
                {
                    orderId = Long.valueOf(original.getRelatedOrderId());
                }
                catch (NumberFormatException | NullPointerException invalidOrder)
                {
                    throw new ServiceException("收入流水缺少有效订单");
                }
            }
            SysOrderCommission order = accountMapper.selectOrderCommissionForUpdate(orderId);
            if (order == null)
            {
                throw new ServiceException("收入流水对应订单不存在");
            }
            if ("refunded".equals(order.getOrderStatus()) || "cancelled".equals(order.getOrderStatus()))
            {
                SysCommissionFlow existing = flowMapper.selectReversalByOriginal(originalFlowId);
                if (existing != null && idempotencyKey.equals(existing.getIdempotencyKey()))
                {
                    return existing;
                }
                throw new ServiceException("已退款或取消的订单不能冲正收入流水");
            }
        }
        original.setIdempotencyKey(idempotencyKey);
        original.setOperatorId(operatorId);
        original.setReversalReason(reason);
        updateUserAccount(original.getUserId(), original.getAmount().negate(), "reversal",
            originalFlowId, reason, original);
        SysCommissionFlow result = flowMapper.selectReversalByOriginal(originalFlowId);
        if (result == null)
        {
            throw new ServiceException("冲正流水保存失败");
        }
        return result;
    }

    private void updateUserAccount(Long userId, BigDecimal amount, String flowType, 
        Long relatedOrderId, String description)
    {
        updateUserAccount(userId, amount, flowType, relatedOrderId, description, null);
    }

    private void updateUserAccount(Long userId, BigDecimal amount, String flowType,
        Long relatedOrderId, String description, SysCommissionFlow reversal)
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
        {
            throw new IllegalStateException("账户入账必须在事务中执行");
        }
        if (userId == null || userId <= 0 || relatedOrderId == null || relatedOrderId <= 0 || amount == null)
        {
            throw new ServiceException("入账必须提供有效用户、金额和唯一业务编号");
        }
        boolean income = "income_self".equals(flowType) || "income_promotion".equals(flowType);
        boolean debit = "refund_self".equals(flowType) || "refund_promotion".equals(flowType)
            || "withdraw".equals(flowType);
        if ((!income && !debit && reversal == null) || (income && amount.signum() < 0) || (debit && amount.signum() > 0))
        {
            throw new ServiceException("流水类型或金额方向不正确");
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        accountMapper.ensureAccount(userId);
        SysAgentCommissionAccount account = accountMapper.selectByUserIdForUpdate(userId);
        if (account == null)
        {
            throw new ServiceException("佣金账户创建失败");
        }

        // 记录变更前余额
        BigDecimal balanceBefore = account.getBalance();

        // 更新账户金额
        if ("income_self".equals(flowType))
        {
            // 自购佣金入账
            account.setTotalEarned(account.getTotalEarned().add(amount));
            account.setSelfEarned(account.getSelfEarned().add(amount));
            account.setBalance(account.getBalance().add(amount));
        }
        else if ("income_promotion".equals(flowType))
        {
            // 推广佣金入账
            account.setTotalEarned(account.getTotalEarned().add(amount));
            account.setPromotionEarned(account.getPromotionEarned().add(amount));
            account.setBalance(account.getBalance().add(amount));
        }
        else if ("refund_self".equals(flowType))
        {
            // 自购退款扣减
            account.setTotalEarned(account.getTotalEarned().add(amount)); // amount为负数
            account.setSelfEarned(account.getSelfEarned().add(amount)); // amount为负数
            account.setBalance(account.getBalance().add(amount)); // amount为负数
        }
        else if ("refund_promotion".equals(flowType))
        {
            // 推广退款扣减
            account.setTotalEarned(account.getTotalEarned().add(amount)); // amount为负数
            account.setPromotionEarned(account.getPromotionEarned().add(amount)); // amount为负数
            account.setBalance(account.getBalance().add(amount)); // amount为负数
        }
        else if ("withdraw".equals(flowType))
        {
            // 提现
            account.setBalance(account.getBalance().add(amount)); // amount为负数
            account.setWithdrawn(account.getWithdrawn().subtract(amount)); // amount为负数，所以用减法
        }
        else if (reversal != null)
        {
            String originalType = reversal.getFlowType();
            account.setBalance(account.getBalance().add(amount));
            if ("income_self".equals(originalType) || "refund_self".equals(originalType))
            {
                account.setTotalEarned(account.getTotalEarned().add(amount));
                account.setSelfEarned(account.getSelfEarned().add(amount));
            }
            else if ("income_promotion".equals(originalType) || "refund_promotion".equals(originalType))
            {
                account.setTotalEarned(account.getTotalEarned().add(amount));
                account.setPromotionEarned(account.getPromotionEarned().add(amount));
            }
            else if ("withdraw".equals(originalType))
            {
                account.setWithdrawn(account.getWithdrawn().subtract(amount));
            }
            else if ("refund".equals(originalType))
            {
                // 旧版退款只改累计收益与余额，不改自购/推广统计。
                account.setTotalEarned(account.getTotalEarned().add(amount));
            }
        }

        account.setUpdateTime(new Date());

        // 记录佣金流水
        SysCommissionFlow flow = new SysCommissionFlow();
        flow.setUserId(userId);
        flow.setFlowType(flowType);
        flow.setAmount(amount);
        flow.setBalanceBefore(balanceBefore);
        flow.setBalanceAfter(account.getBalance());
        // 将Long类型的relatedOrderId转换为String类型
        if (reversal == null)
        {
            flow.setOrderCommissionId(relatedOrderId);
            flow.setRelatedOrderId(relatedOrderId.toString());
        }
        else
        {
            flow.setOriginalFlowId(reversal.getId());
            flow.setIdempotencyKey(reversal.getIdempotencyKey());
            flow.setOperatorId(reversal.getOperatorId());
            flow.setReversalReason(reversal.getReversalReason());
            flow.setRelatedOrderId(reversal.getRelatedOrderId());
        }
        flow.setDescription(description);
        flow.setCreateTime(new Date());
        // 先尝试追加流水，避免对尚不存在的唯一键加间隙锁。
        // 唯一键冲突只回滚INSERT语句；当前事务仍持有账户行锁。
        try
        {
            if (flowMapper.insertSysCommissionFlow(flow) != 1)
            {
                throw new ServiceException("佣金流水保存失败");
            }
        }
        catch (DuplicateKeyException duplicate)
        {
            if (reversal != null)
            {
                SysCommissionFlow existing = flowMapper.selectReversalByOriginal(reversal.getId());
                if (existing != null && reversal.getIdempotencyKey().equals(existing.getIdempotencyKey()))
                {
                    return;
                }
                throw new ServiceException("该流水已冲正或幂等键已被使用");
            }
            SysCommissionFlow existing = flowMapper.selectByBusinessKey(userId, flowType, relatedOrderId);
            if (existing == null)
            {
                throw duplicate;
            }
            if (existing.getAmount().compareTo(amount) != 0)
            {
                throw new ServiceException("同一业务编号重复入账的金额不一致");
            }
            return;
        }
        if (accountMapper.updateSysAgentCommissionAccount(account) != 1)
        {
            throw new ServiceException("佣金账户更新失败");
        }
    }

    /** Contract postings join the caller's transaction (including withdrawal state changes).
     * Positive amount describes one balanced transfer; balance_before/after refer to available money.
     * Legacy order_commission_id is intentionally null: event identity is the contract business key.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public LedgerPosting updateUserAccount(PostingCommand c)
    {
        validatePosting(c);
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("账户入账必须在事务中执行");
        accountMapper.ensureAccount(c.userId());
        SysAgentCommissionAccount a = accountMapper.selectByUserIdForUpdate(c.userId());
        var debit = c.debitAccount();
        var credit = c.creditAccount();
        var available = AccountType.USER_AVAILABLE;
        var frozen = AccountType.USER_FROZEN;
        var withdrawing = AccountType.USER_WITHDRAWING;
        var payout = AccountType.PAYOUT;
        BigDecimal before = a.getBalance(), amount = c.amount();
        if (debit == available) a.setBalance(a.getBalance().subtract(amount));
        if (credit == available) a.setBalance(a.getBalance().add(amount));
        if (debit == frozen) a.setFrozenAmount(a.getFrozenAmount().subtract(amount));
        if (credit == frozen) a.setFrozenAmount(a.getFrozenAmount().add(amount));
        if (debit == withdrawing) a.setWithdrawingAmount(a.getWithdrawingAmount().subtract(amount));
        if (credit == withdrawing) a.setWithdrawingAmount(a.getWithdrawingAmount().add(amount));
        if (credit == payout) a.setWithdrawn(a.getWithdrawn().add(amount));
        // INSERT first avoids locking missing business keys (gap-lock deadlocks between users).
        // Only the caller's transaction commits this row; any admission error below rolls it back.
        try
        {
            ledgerJdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,balance_before,balance_after,"
                    + "related_order_id,business_type,business_id,account,debit_account,credit_account,idempotency_key,"
                    + "description,operator_id,create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",
                c.userId(), c.type(), amount, before, a.getBalance(), c.orderLineId() == null ? null : c.orderLineId().toString(),
                c.businessType(), c.businessId(), c.account().name(), debit.name(), credit.name(), c.businessKey(), c.reason(), c.operatorId());
        }
        catch (DuplicateKeyException duplicate)
        {
            return replayPosting(c, duplicate);
        }
        if (credit == withdrawing && (!"0".equals(a.getStatus()) || before.compareTo(amount) < 0))
            throw new ServiceException("账户不可提现或可提现余额不足");
        if (a.getFrozenAmount().signum() < 0 || a.getWithdrawingAmount().signum() < 0)
            throw new ServiceException("冻结或提现中金额不足");
        // Prevent competing terminal postings from consuming another withdrawal's reservation.
        if (debit == withdrawing)
        {
            List<Map<String, Object>> applications = ledgerJdbc.queryForList(
                "SELECT user_id,amount FROM sys_commission_flow WHERE business_type='WITHDRAW_APPLY' AND business_id=? FOR UPDATE",
                c.businessId());
            if (applications.size() != 1
                || ((Number) applications.getFirst().get("user_id")).longValue() != c.userId()
                || amount.compareTo((BigDecimal) applications.getFirst().get("amount")) != 0
                || ledgerJdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE business_id=? "
                    + "AND business_type IN ('WITHDRAW_SUCCESS','WITHDRAW_RETURN') AND idempotency_key<>? FOR UPDATE",
                    Long.class, c.businessId(), c.businessKey()) != 0)
                throw new ServiceException("提现预留不存在或已处理");
        }
        BigDecimal earnings = BigDecimal.ZERO;
        if (debit == frozen && credit == available) earnings = amount;
        if (debit == available && (credit == AccountType.REBATE_COST || credit == AccountType.REWARD_COST))
            earnings = amount.negate();
        a.setTotalEarned(a.getTotalEarned().add(earnings));
        if ("income_self".equals(c.type()) || "refund_self".equals(c.type()))
            a.setSelfEarned(a.getSelfEarned().add(earnings));
        if ("income_promotion".equals(c.type()) || "refund_promotion".equals(c.type()))
            a.setPromotionEarned(a.getPromotionEarned().add(earnings));
        // Existing updateUserAccount boundary owns every balance write, including the new pockets.
        if (accountMapper.updateSysAgentCommissionAccount(a) != 1) throw new ServiceException("佣金账户更新失败");
        ledgerJdbc.update("UPDATE sys_agent_commission_account SET withdrawing_amount=?,version=version+1 WHERE user_id=?",
            a.getWithdrawingAmount(), c.userId());
        Long id = ledgerJdbc.queryForObject("SELECT id FROM sys_commission_flow WHERE idempotency_key=?", Long.class, c.businessKey());
        return new LedgerPosting(id, c.businessKey(), a.getBalance(), false);
    }

    private LedgerPosting replayPosting(PostingCommand c, DuplicateKeyException duplicate)
    {
        List<Map<String, Object>> prior = ledgerJdbc.queryForList(
            "SELECT * FROM sys_commission_flow WHERE idempotency_key=? OR "
                + "(business_type=? AND business_id=? AND account=?) FOR UPDATE",
            c.businessKey(), c.businessType(), c.businessId(), c.account().name());
        if (prior.isEmpty()) throw duplicate;
        Map<String, Object> row = prior.getFirst();
        if (prior.size() != 1 || !c.businessKey().equals(row.get("idempotency_key"))
            || !c.businessType().equals(row.get("business_type")) || !c.businessId().equals(row.get("business_id"))
            || !c.userId().equals(((Number) row.get("user_id")).longValue())
            || !c.type().equals(row.get("flow_type")) || !c.account().name().equals(row.get("account"))
            || !c.debitAccount().name().equals(row.get("debit_account"))
            || !c.creditAccount().name().equals(row.get("credit_account"))
            || !Objects.equals(c.orderLineId() == null ? null : c.orderLineId().toString(), row.get("related_order_id"))
            || c.amount().compareTo((BigDecimal) row.get("amount")) != 0)
            throw new ServiceException("幂等键已用于不同分录");
        return new LedgerPosting(((Number) row.get("id")).longValue(), c.businessKey(),
            (BigDecimal) row.get("balance_after"), true);
    }

    private void validatePosting(PostingCommand c)
    {
        if (c == null || c.userId() == null || c.userId() <= 0 || c.amount() == null || c.amount().signum() <= 0
            || c.amount().stripTrailingZeros().scale() > 2 || c.amount().precision() - c.amount().scale() > 12
            || c.account() == null || c.account() != c.debitAccount() || c.creditAccount() == null)
            throw new ServiceException("无效账本分录");
        if (c.businessKey() == null || c.businessKey().isBlank() || c.businessKey().length() > 128
            || c.businessType() == null || c.businessType().isBlank() || c.businessType().length() > 64
            || c.businessId() == null || c.businessId().isBlank() || c.businessId().length() > 128
            || c.type() == null || c.type().isBlank() || c.type().length() > 20
            || c.reason() == null || c.reason().length() > 500)
            throw new ServiceException("分录缺少有效业务标识");
        String route = c.debitAccount().name() + ":" + c.creditAccount().name();
        boolean legal = switch (route) {
            case "REBATE_COST:USER_FROZEN", "REWARD_COST:USER_FROZEN", "USER_FROZEN:USER_AVAILABLE",
                 "USER_FROZEN:REBATE_COST", "USER_FROZEN:REWARD_COST", "USER_AVAILABLE:REBATE_COST",
                 "USER_AVAILABLE:REWARD_COST" -> !c.businessType().startsWith("WITHDRAW_");
            case "USER_AVAILABLE:USER_WITHDRAWING" -> "WITHDRAW_APPLY".equals(c.businessType());
            case "USER_WITHDRAWING:PAYOUT" -> "WITHDRAW_SUCCESS".equals(c.businessType());
            case "USER_WITHDRAWING:USER_AVAILABLE" -> "WITHDRAW_RETURN".equals(c.businessType());
            default -> false;
        };
        if (!legal) throw new ServiceException("不支持的账本路径");
    }

    /** 多个账户按固定顺序加锁，避免互为推荐人的并发订单交叉锁定。 */
    private void lockPostingAccounts(SysOrderCommission order)
    {
        if (order.getBuyerUserId() == null || order.getBuyerUserId() <= 0)
        {
            throw new ServiceException("订单缺少有效购买用户");
        }
        TreeSet<Long> userIds = new TreeSet<>();
        userIds.add(order.getBuyerUserId());
        if ("1".equals(order.getHasReferrer()) && order.getReferrerUserId() != null)
        {
            if (order.getReferrerUserId() <= 0)
            {
                throw new ServiceException("订单推荐用户无效");
            }
            userIds.add(order.getReferrerUserId());
        }
        for (Long userId : userIds)
        {
            accountMapper.ensureAccount(userId);
            accountMapper.selectByUserIdForUpdate(userId);
        }
    }

    /**
     * 获取自购佣金比例
     */
    @Override
    public BigDecimal getSelfCommissionRate()
    {
        SysCommissionConfig config = commissionConfigMapper
            .selectSysCommissionConfigByKey("self_rate");
        return requireRate(config, "self_rate");
    }

    /**
     * 获取推广佣金比例
     */
    @Override
    public BigDecimal getPromotionCommissionRate()
    {
        SysCommissionConfig config = commissionConfigMapper
            .selectSysCommissionConfigByKey("promotion_rate");
        return requireRate(config, "promotion_rate");
    }

    /**
     * 更新佣金配置
     */
    @Override
    @Transactional
    public boolean updateCommissionConfig(String configKey, BigDecimal configValue)
    {
        SysCommissionConfig config = commissionConfigMapper.selectSysCommissionConfigByKey(configKey);
        if (config == null)
        {
            config = new SysCommissionConfig();
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setCreateTime(new Date());
            return commissionConfigService.insertSysCommissionConfig(config) > 0;
        }
        config.setConfigValue(configValue);
        config.setUpdateTime(new Date());
        return commissionConfigService.updateSysCommissionConfig(config) > 0;
    }

    private BigDecimal requireRate(SysCommissionConfig config, String key)
    {
        if (config == null || config.getConfigValue() == null || !"0".equals(config.getStatus()))
        {
            throw new ServiceException("缺少佣金比例配置: " + key);
        }
        String otherKey = "self_rate".equals(key) ? "promotion_rate" : "self_rate";
        SysCommissionConfig other = commissionConfigMapper.selectSysCommissionConfigByKey(otherKey);
        if (other == null || other.getConfigValue() == null || !"0".equals(other.getStatus()))
        {
            throw new ServiceException("缺少佣金比例配置: " + otherKey);
        }
        BigDecimal rate = config.getConfigValue();
        BigDecimal otherRate = other.getConfigValue();
        BigDecimal max = new BigDecimal("100");
        if (rate.signum() < 0 || otherRate.signum() < 0 || rate.compareTo(max) > 0
            || otherRate.compareTo(max) > 0 || rate.add(otherRate).compareTo(max) > 0)
        {
            throw new ServiceException("佣金比例配置无效：各比例须在0到100之间，合计不能超过100%");
        }
        return rate;
    }
}
