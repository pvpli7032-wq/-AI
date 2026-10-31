package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.TreeSet;
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
    private SysOrderCommissionMapper orderCommissionMapper;

    @Autowired
    private SysAgentCommissionAccountMapper accountMapper;

    @Autowired
    private SysCommissionFlowMapper flowMapper;

    @Autowired
    private SysWechatUserMapper wechatUserMapper;

    @Autowired
    private com.ruoyi.system.mapper.SysTbkAgentApplyMapper agentApplyMapper;

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
            orderCommission.setOrderTime(sdf.parse(orderTime));
        } catch (java.text.ParseException e) {
            orderCommission.setOrderTime(new Date());
        }
        orderCommission.setOrderStatus("pending"); // 待结算
        orderCommission.setSettleStatus("unsettled"); // 未结算
        orderCommission.setCreateTime(new Date());

        // 查询购买用户的代理申请信息，判断是否有推荐人
        com.ruoyi.system.domain.SysTbkAgentApply buyerAgentApply = agentApplyMapper.selectByUserId(buyerUserId);
        if (buyerAgentApply == null)
        {
            throw new RuntimeException("用户不是代理，无法计算佣金");
        }

        // 获取佣金配置
        BigDecimal selfRate = getSelfCommissionRate();
        BigDecimal promotionRate = getPromotionCommissionRate();

        // 判断是否有推荐人（基于代理申请表的 referrer_invitation_code 字段）
        boolean hasReferrer = buyerAgentApply.getReferrerInvitationCode() != null 
            && !buyerAgentApply.getReferrerInvitationCode().isEmpty();
        
        orderCommission.setHasReferrer(hasReferrer ? "1" : "0");

        if (hasReferrer)
        {
            // 有推荐人：根据推荐人邀请码查找推荐人的代理申请信息
            com.ruoyi.system.domain.SysTbkAgentApply referrerAgentApply = agentApplyMapper
                .selectByInvitationCode(buyerAgentApply.getReferrerInvitationCode());
            
            if (referrerAgentApply != null)
            {
                orderCommission.setReferrerUserId(referrerAgentApply.getUserId());
                
                // 计算自购佣金（购买者获得）
                BigDecimal selfCommissionAmount = estimatedCommission
                    .multiply(selfRate)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                
                // 计算推广佣金（推荐人获得）
                BigDecimal promotionCommissionAmount = estimatedCommission
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
            actualPromotionAmount = actualCommission
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
        BigDecimal selfAmount = orderCommission.getSelfCommissionAmount();
        if (selfAmount != null && selfAmount.signum() > 0)
        {
            updateUserAccount(orderCommission.getBuyerUserId(), selfAmount.negate(), "refund_self",
                orderCommission.getId(), "订单退款扣减自购佣金");
        }
        if ("1".equals(orderCommission.getHasReferrer()) && orderCommission.getReferrerUserId() != null)
        {
            BigDecimal promotionAmount = orderCommission.getPromotionCommissionAmount();
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

    /**
     * 更新用户佣金账户
     */
    private void updateUserAccount(Long userId, BigDecimal amount, String flowType, 
        Long relatedOrderId, String description)
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
        if ((!income && !debit) || (income && amount.signum() < 0) || (debit && amount.signum() > 0))
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

        account.setUpdateTime(new Date());

        // 记录佣金流水
        SysCommissionFlow flow = new SysCommissionFlow();
        flow.setUserId(userId);
        flow.setFlowType(flowType);
        flow.setAmount(amount);
        flow.setBalanceBefore(balanceBefore);
        flow.setBalanceAfter(account.getBalance());
        // 将Long类型的relatedOrderId转换为String类型
        flow.setOrderCommissionId(relatedOrderId);
        flow.setRelatedOrderId(relatedOrderId.toString());
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
        return config != null ? config.getConfigValue() : new BigDecimal("40");
    }

    /**
     * 获取推广佣金比例
     */
    @Override
    public BigDecimal getPromotionCommissionRate()
    {
        SysCommissionConfig config = commissionConfigMapper
            .selectSysCommissionConfigByKey("promotion_rate");
        return config != null ? config.getConfigValue() : new BigDecimal("5");
    }

    /**
     * 更新佣金配置
     */
    @Override
    @Transactional
    public boolean updateCommissionConfig(String configKey, BigDecimal configValue)
    {
        SysCommissionConfig config = commissionConfigMapper
            .selectSysCommissionConfigByKey(configKey);
        
        if (config == null)
        {
            // 创建新配置
            config = new SysCommissionConfig();
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setCreateTime(new Date());
            return commissionConfigMapper.insertSysCommissionConfig(config) > 0;
        }
        else
        {
            // 更新配置
            config.setConfigValue(configValue);
            config.setUpdateTime(new Date());
            return commissionConfigMapper.updateSysCommissionConfig(config) > 0;
        }
    }
}
