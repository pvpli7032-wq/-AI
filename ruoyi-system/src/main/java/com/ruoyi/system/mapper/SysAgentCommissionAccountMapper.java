package com.ruoyi.system.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.domain.SysOrderCommission;

/**
 * 代理佣金账户Mapper接口
 * 
 * @author ruoyi
 * @date 2026-04-08
 */
public interface SysAgentCommissionAccountMapper 
{
    /** 并发首次入账时只创建一个零余额账户，不覆盖已有余额。 */
    int ensureAccount(@Param("userId") Long userId);

    /** 仅在入账事务内使用，锁定账户直至提交。 */
    SysAgentCommissionAccount selectByUserIdForUpdate(@Param("userId") Long userId);

    /** 结算和退款先锁定订单，再按用户ID顺序锁定账户。 */
    SysOrderCommission selectOrderCommissionForUpdate(@Param("id") Long id);

    /** 保存结算字段；与账户、流水处于同一事务。 */
    int updateOrderSettlement(SysOrderCommission order);

    /** 冻结/解冻只更新状态，不触及任何金额。 */
    int updateAccountStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * 查询代理佣金账户
     * 
     * @param id 代理佣金账户主键
     * @return 代理佣金账户
     */
    public SysAgentCommissionAccount selectSysAgentCommissionAccountById(Long id);

    /**
     * 根据用户ID查询代理佣金账户
     * 
     * @param userId 用户ID
     * @return 代理佣金账户
     */
    public SysAgentCommissionAccount selectSysAgentCommissionAccountByUserId(Long userId);

    /**
     * 根据用户ID查询代理佣金账户（别名方法）
     * 
     * @param userId 用户ID
     * @return 代理佣金账户
     */
    public SysAgentCommissionAccount selectByUserId(Long userId);

    /**
     * 查询代理佣金账户列表
     * 
     * @param sysAgentCommissionAccount 代理佣金账户
     * @return 代理佣金账户集合
     */
    public List<SysAgentCommissionAccount> selectSysAgentCommissionAccountList(SysAgentCommissionAccount sysAgentCommissionAccount);

    /**
     * 新增代理佣金账户
     * 
     * @param sysAgentCommissionAccount 代理佣金账户
     * @return 结果
     */
    public int insertSysAgentCommissionAccount(SysAgentCommissionAccount sysAgentCommissionAccount);

    /**
     * 修改代理佣金账户
     * 
     * @param sysAgentCommissionAccount 代理佣金账户
     * @return 结果
     */
    public int updateSysAgentCommissionAccount(SysAgentCommissionAccount sysAgentCommissionAccount);

    /**
     * 删除代理佣金账户
     * 
     * @param id 代理佣金账户主键
     * @return 结果
     */
    public int deleteSysAgentCommissionAccountById(Long id);

    /**
     * 批量删除代理佣金账户
     * 
     * @param ids 需要删除的数据主键集合
     * @return 结果
     */
    public int deleteSysAgentCommissionAccountByIds(Long[] ids);
}
