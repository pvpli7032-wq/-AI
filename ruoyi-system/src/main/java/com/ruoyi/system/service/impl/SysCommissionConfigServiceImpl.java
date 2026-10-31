package com.ruoyi.system.service.impl;

import java.util.List;
import java.math.BigDecimal;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.system.mapper.SysCommissionConfigMapper;
import com.ruoyi.system.domain.SysCommissionConfig;
import com.ruoyi.system.service.ISysCommissionConfigService;

/**
 * 佣金配置Service业务层处理
 * 
 * @author ruoyi
 */
@Service
public class SysCommissionConfigServiceImpl implements ISysCommissionConfigService 
{
    @Autowired
    private SysCommissionConfigMapper sysCommissionConfigMapper;

    /**
     * 查询佣金配置
     * 
     * @param configKey 佣金配置主键
     * @return 佣金配置
     */
    @Override
    public SysCommissionConfig selectSysCommissionConfigByKey(String configKey)
    {
        return sysCommissionConfigMapper.selectSysCommissionConfigByKey(configKey);
    }

    /**
     * 查询佣金配置列表
     * 
     * @param sysCommissionConfig 佣金配置
     * @return 佣金配置
     */
    @Override
    public List<SysCommissionConfig> selectSysCommissionConfigList(SysCommissionConfig sysCommissionConfig)
    {
        return sysCommissionConfigMapper.selectSysCommissionConfigList(sysCommissionConfig);
    }

    /**
     * 新增佣金配置
     * 
     * @param sysCommissionConfig 佣金配置
     * @return 结果
     */
    @Override
    @Transactional
    public int insertSysCommissionConfig(SysCommissionConfig sysCommissionConfig)
    {
        validateRateChange(sysCommissionConfig);
        return sysCommissionConfigMapper.insertSysCommissionConfig(sysCommissionConfig);
    }

    /**
     * 修改佣金配置
     * 
     * @param sysCommissionConfig 佣金配置
     * @return 结果
     */
    @Override
    @Transactional
    public int updateSysCommissionConfig(SysCommissionConfig sysCommissionConfig)
    {
        validateRateChange(sysCommissionConfig);
        return sysCommissionConfigMapper.updateSysCommissionConfig(sysCommissionConfig);
    }

    private void validateRateChange(SysCommissionConfig config)
    {
        String key = config.getConfigKey();
        if (!"self_rate".equals(key) && !"promotion_rate".equals(key))
        {
            return;
        }
        BigDecimal value = config.getConfigValue();
        if (value == null || value.signum() < 0 || value.compareTo(new BigDecimal("100")) > 0)
        {
            throw new ServiceException("佣金比例必须在0到100之间");
        }
        String otherKey = "self_rate".equals(key) ? "promotion_rate" : "self_rate";
        for (SysCommissionConfig existing : sysCommissionConfigMapper.selectRatesForUpdate())
        {
            if (otherKey.equals(existing.getConfigKey()) && existing.getConfigValue() != null
                && value.add(existing.getConfigValue()).compareTo(new BigDecimal("100")) > 0)
            {
                throw new ServiceException("自购佣金比例和推广佣金比例之和不能超过100%");
            }
        }
    }

    /**
     * 批量删除佣金配置
     * 
     * @param configKeys 需要删除的佣金配置主键
     * @return 结果
     */
    @Override
    public int deleteSysCommissionConfigByKeys(String[] configKeys)
    {
        return sysCommissionConfigMapper.deleteSysCommissionConfigByKeys(configKeys);
    }

    /**
     * 删除佣金配置信息
     * 
     * @param configKey 佣金配置主键
     * @return 结果
     */
    @Override
    public int deleteSysCommissionConfigByKey(String configKey)
    {
        return sysCommissionConfigMapper.deleteSysCommissionConfigByKey(configKey);
    }
}
