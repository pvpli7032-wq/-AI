package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.service.ISysAgentCommissionAccountService;

/**
 * 佣金账户Service业务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysAgentCommissionAccountServiceImpl implements ISysAgentCommissionAccountService 
{
    @Autowired
    private SysAgentCommissionAccountMapper sysAgentCommissionAccountMapper;

    /**
     * 查询佣金账户
     * 
     * @param id 佣金账户主键
     * @return 佣金账户
     */
    @Override
    public SysAgentCommissionAccount selectSysAgentCommissionAccountById(Long id)
    {
        return sysAgentCommissionAccountMapper.selectSysAgentCommissionAccountById(id);
    }

    /**
     * 根据用户ID查询佣金账户
     * 
     * @param userId 用户ID
     * @return 佣金账户
     */
    @Override
    public SysAgentCommissionAccount selectByUserId(Long userId)
    {
        return sysAgentCommissionAccountMapper.selectByUserId(userId);
    }

    /**
     * 查询佣金账户列表
     * 
     * @param sysAgentCommissionAccount 佣金账户
     * @return 佣金账户
     */
    @Override
    public List<SysAgentCommissionAccount> selectSysAgentCommissionAccountList(SysAgentCommissionAccount sysAgentCommissionAccount)
    {
        return sysAgentCommissionAccountMapper.selectSysAgentCommissionAccountList(sysAgentCommissionAccount);
    }

    /**
     * 新增佣金账户
     * 
     * @param sysAgentCommissionAccount 佣金账户
     * @return 结果
     */
    @Override
    public int insertSysAgentCommissionAccount(SysAgentCommissionAccount sysAgentCommissionAccount)
    {
        for (BigDecimal amount : monetaryFields(sysAgentCommissionAccount))
        {
            if (amount != null && amount.signum() != 0)
            {
                throw new ServiceException("新账户金额必须为零，金额变动请通过统一入账方法处理");
            }
        }
        sysAgentCommissionAccount.setBalance(BigDecimal.ZERO);
        sysAgentCommissionAccount.setFrozenAmount(BigDecimal.ZERO);
        sysAgentCommissionAccount.setWithdrawn(BigDecimal.ZERO);
        sysAgentCommissionAccount.setTotalEarned(BigDecimal.ZERO);
        sysAgentCommissionAccount.setSelfEarned(BigDecimal.ZERO);
        sysAgentCommissionAccount.setPromotionEarned(BigDecimal.ZERO);
        if (sysAgentCommissionAccount.getStatus() == null || sysAgentCommissionAccount.getStatus().isEmpty())
        {
            sysAgentCommissionAccount.setStatus("0");
        }
        return sysAgentCommissionAccountMapper.insertSysAgentCommissionAccount(sysAgentCommissionAccount);
    }

    /**
     * 修改佣金账户
     * 
     * @param sysAgentCommissionAccount 佣金账户
     * @return 结果
     */
    @Override
    public int updateSysAgentCommissionAccount(SysAgentCommissionAccount sysAgentCommissionAccount)
    {
        for (BigDecimal amount : monetaryFields(sysAgentCommissionAccount))
        {
            if (amount != null)
            {
                throw new ServiceException("账户金额不能直接修改，请通过统一入账方法处理");
            }
        }
        return sysAgentCommissionAccountMapper.updateSysAgentCommissionAccount(sysAgentCommissionAccount);
    }

    @Override
    public int freezeAccount(Long accountId)
    {
        return changeAccountStatus(accountId, "1");
    }

    @Override
    public int unfreezeAccount(Long accountId)
    {
        return changeAccountStatus(accountId, "0");
    }

    private int changeAccountStatus(Long accountId, String status)
    {
        if (accountId == null || accountId <= 0)
        {
            throw new ServiceException("佣金账户ID无效");
        }
        return sysAgentCommissionAccountMapper.updateAccountStatus(accountId, status);
    }

    private BigDecimal[] monetaryFields(SysAgentCommissionAccount account)
    {
        return new BigDecimal[] { account.getBalance(), account.getFrozenAmount(), account.getWithdrawn(),
            account.getTotalEarned(), account.getSelfEarned(), account.getPromotionEarned() };
    }

    /**
     * 批量删除佣金账户
     * 
     * @param ids 需要删除的佣金账户主键
     * @return 结果
     */
    @Override
    public int deleteSysAgentCommissionAccountByIds(Long[] ids)
    {
        return sysAgentCommissionAccountMapper.deleteSysAgentCommissionAccountByIds(ids);
    }

    /**
     * 删除佣金账户信息
     * 
     * @param id 佣金账户主键
     * @return 结果
     */
    @Override
    public int deleteSysAgentCommissionAccountById(Long id)
    {
        return sysAgentCommissionAccountMapper.deleteSysAgentCommissionAccountById(id);
    }
}
