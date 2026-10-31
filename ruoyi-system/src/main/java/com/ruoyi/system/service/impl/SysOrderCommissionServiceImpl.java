package com.ruoyi.system.service.impl;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.Arrays;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.service.ISysOrderCommissionService;

/**
 * 订单佣金Service业务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysOrderCommissionServiceImpl implements ISysOrderCommissionService 
{
    @Autowired
    private SysOrderCommissionMapper sysOrderCommissionMapper;

    @Autowired
    private SysAgentCommissionAccountMapper accountMapper;

    /**
     * 查询订单佣金
     * 
     * @param id 订单佣金主键
     * @return 订单佣金
     */
    @Override
    public SysOrderCommission selectSysOrderCommissionById(Long id)
    {
        return sysOrderCommissionMapper.selectSysOrderCommissionById(id);
    }

    /**
     * 根据订单ID和平台类型查询订单佣金
     * 
     * @param orderId 订单ID
     * @param platformType 平台类型
     * @return 订单佣金
     */
    @Override
    public SysOrderCommission selectSysOrderCommissionByOrderIdAndPlatformType(String orderId, String platformType)
    {
        return sysOrderCommissionMapper.selectSysOrderCommissionByOrderIdAndPlatformType(orderId, platformType);
    }

    /**
     * 查询订单佣金列表
     * 
     * @param sysOrderCommission 订单佣金
     * @return 订单佣金
     */
    @Override
    public List<SysOrderCommission> selectSysOrderCommissionList(SysOrderCommission sysOrderCommission)
    {
        return sysOrderCommissionMapper.selectSysOrderCommissionList(sysOrderCommission);
    }

    /**
     * 新增订单佣金
     * 
     * @param sysOrderCommission 订单佣金
     * @return 结果
     */
    @Override
    public int insertSysOrderCommission(SysOrderCommission sysOrderCommission)
    {
        return sysOrderCommissionMapper.insertSysOrderCommission(sysOrderCommission);
    }

    /**
     * 修改订单佣金
     * 
     * @param sysOrderCommission 订单佣金
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateSysOrderCommission(SysOrderCommission request)
    {
        if (request == null || request.getId() == null || request.getId() <= 0)
        {
            throw new ServiceException("订单ID无效");
        }
        SysOrderCommission current = lockOrder(request.getId());
        boolean protectedOrder = hasAccounting(current);
        // Even pending orders cannot change amounts, rates or lifecycle through this route.
        requireUnchanged(request.getOrderAmount(), current.getOrderAmount());
        requireUnchanged(request.getTotalCommission(), current.getTotalCommission());
        requireUnchanged(request.getSelfCommissionRate(), current.getSelfCommissionRate());
        requireUnchanged(request.getSelfCommissionAmount(), current.getSelfCommissionAmount());
        requireUnchanged(request.getPromotionCommissionRate(), current.getPromotionCommissionRate());
        requireUnchanged(request.getPromotionCommissionAmount(), current.getPromotionCommissionAmount());
        requireUnchanged(request.getPlatformAmount(), current.getPlatformAmount());
        requireUnchanged(request.getEstimatedCommission(), current.getEstimatedCommission());
        requireUnchanged(request.getCommissionRate(), current.getCommissionRate());
        requireUnchanged(request.getActualCommission(), current.getActualCommission());
        requireUnchanged(request.getOrderStatus(), current.getOrderStatus());
        requireUnchanged(request.getSettleStatus(), current.getSettleStatus());
        requireUnchanged(request.getSettleTime(), current.getSettleTime());
        if (protectedOrder)
        {
            requireUnchanged(request.getOrderId(), current.getOrderId());
            requireUnchanged(request.getPlatformType(), current.getPlatformType());
            requireUnchanged(request.getItemId(), current.getItemId());
            requireUnchanged(request.getItemTitle(), current.getItemTitle());
            requireUnchanged(request.getAgentUserId(), current.getAgentUserId());
            requireUnchanged(request.getBuyerUserId(), current.getBuyerUserId());
            requireUnchanged(request.getHasReferrer(), current.getHasReferrer());
            requireUnchanged(request.getReferrerUserId(), current.getReferrerUserId());
            requireUnchanged(request.getOrderTime(), current.getOrderTime());
        }
        SysOrderCommission patch = new SysOrderCommission();
        patch.setId(current.getId());
        patch.setRemark(request.getRemark());
        if (!protectedOrder)
        {
            patch.setOrderId(request.getOrderId());
            patch.setPlatformType(request.getPlatformType());
            patch.setItemId(request.getItemId());
            patch.setItemTitle(request.getItemTitle());
            patch.setAgentUserId(request.getAgentUserId());
            patch.setBuyerUserId(request.getBuyerUserId());
            patch.setHasReferrer(request.getHasReferrer());
            patch.setReferrerUserId(request.getReferrerUserId());
            patch.setOrderTime(request.getOrderTime());
        }
        if (request.getRemark() == null && (protectedOrder || !hasPendingEdit(request)))
        {
            throw new ServiceException("没有可修改的订单字段");
        }
        return sysOrderCommissionMapper.updateSysOrderCommission(patch);
    }

    private boolean hasPendingEdit(SysOrderCommission order)
    {
        return order.getOrderId() != null || order.getPlatformType() != null || order.getItemId() != null
            || order.getItemTitle() != null || order.getAgentUserId() != null || order.getBuyerUserId() != null
            || order.getHasReferrer() != null || order.getReferrerUserId() != null || order.getOrderTime() != null;
    }

    private void requireUnchanged(Object requested, Object existing)
    {
        if (requested == null) return;
        boolean same = requested instanceof java.math.BigDecimal && existing instanceof java.math.BigDecimal
            ? ((java.math.BigDecimal) requested).compareTo((java.math.BigDecimal) existing) == 0
            : Objects.equals(requested, existing);
        if (!same) throw new ServiceException("订单金额、状态或已入账订单字段不可直接修改");
    }

    private SysOrderCommission lockOrder(Long id)
    {
        SysOrderCommission order = accountMapper.selectOrderCommissionForUpdate(id);
        if (order == null) throw new ServiceException("订单不存在");
        return order;
    }

    private boolean hasAccounting(SysOrderCommission order)
    {
        return "settled".equals(order.getSettleStatus()) || "confirmed".equals(order.getOrderStatus())
            || "settled".equals(order.getOrderStatus()) || "refunded".equals(order.getOrderStatus())
            || sysOrderCommissionMapper.selectExistingFlowForUpdate(order.getId()) != null;
    }

    /**
     * 批量删除订单佣金
     * 
     * @param ids 需要删除的订单佣金主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteSysOrderCommissionByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0 || Arrays.stream(ids).anyMatch(id -> id == null || id <= 0))
            throw new ServiceException("订单ID无效");
        TreeSet<Long> orderedIds = new TreeSet<>(Arrays.asList(ids));
        for (Long id : orderedIds)
        {
            if (hasAccounting(lockOrder(id)))
                throw new ServiceException("已结算或已有流水的订单不能删除");
        }
        return sysOrderCommissionMapper.deleteSysOrderCommissionByIds(orderedIds.toArray(new Long[0]));
    }

    /**
     * 删除订单佣金信息
     * 
     * @param id 订单佣金主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteSysOrderCommissionById(Long id)
    {
        return deleteSysOrderCommissionByIds(new Long[] { id });
    }
}
