package com.ruoyi.system.cps.risk;

import java.math.BigDecimal;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysConfigService;

/** All business thresholds come from sys_config; zero explicitly disables a signal. */
public final class RiskPolicy {
    private final ISysConfigService configs;

    public RiskPolicy(ISysConfigService configs) {
        this.configs = configs;
    }

    public BigDecimal decimal(String key) {
        String value = configs.selectConfigByKey(key);
        if (value == null || value.isBlank()) {
            throw new ServiceException("风控配置缺失：" + key);
        }
        try {
            BigDecimal result = new BigDecimal(value.trim());
            if (result.signum() < 0 || result.precision() > 24 || result.scale() > 8 || result.scale() < -12) {
                throw new NumberFormatException();
            }
            return result;
        } catch (NumberFormatException ex) {
            throw new ServiceException("风控配置无效：" + key);
        }
    }

    public long count(String key) {
        try {
            return decimal(key).longValueExact();
        } catch (ArithmeticException ex) {
            throw new ServiceException("风控配置必须为非负整数：" + key);
        }
    }
}
