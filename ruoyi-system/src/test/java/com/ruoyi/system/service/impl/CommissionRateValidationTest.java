package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysCommissionConfig;
import com.ruoyi.system.mapper.SysCommissionConfigMapper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;

public class CommissionRateValidationTest
{
    private SysCommissionConfigMapper mapper;
    private SysCommissionConfigServiceImpl service;

    private SysCommissionConfig rate(String key, String value)
    {
        SysCommissionConfig config = new SysCommissionConfig();
        config.setConfigKey(key);
        config.setConfigValue(new BigDecimal(value));
        return config;
    }

    @Before
    public void setUp()
    {
        mapper = mock(SysCommissionConfigMapper.class);
        service = new SysCommissionConfigServiceImpl();
        ReflectionTestUtils.setField(service, "sysCommissionConfigMapper", mapper);
    }

    @Test
    public void rejectsOutOfRangeAndMissingValues()
    {
        assertThrows(ServiceException.class, () -> service.updateSysCommissionConfig(rate("self_rate", "-0.01")));
        assertThrows(ServiceException.class, () -> service.insertSysCommissionConfig(rate("promotion_rate", "100.01")));
        SysCommissionConfig missing = new SysCommissionConfig();
        missing.setConfigKey("self_rate");
        assertThrows(ServiceException.class, () -> service.updateSysCommissionConfig(missing));
        verifyNoInteractions(mapper);
    }

    @Test
    public void rejectsCombinedRateAboveOneHundredOnBothPaths()
    {
        when(mapper.selectRatesForUpdate()).thenReturn(Arrays.asList(rate("self_rate", "80"), rate("promotion_rate", "20")));
        assertThrows(ServiceException.class, () -> service.updateSysCommissionConfig(rate("self_rate", "80.01")));
        assertThrows(ServiceException.class, () -> service.insertSysCommissionConfig(rate("promotion_rate", "20.01")));
        verify(mapper, never()).updateSysCommissionConfig(any());
        verify(mapper, never()).insertSysCommissionConfig(any());
    }

    @Test
    public void acceptsCombinedRateExactlyOneHundred()
    {
        SysCommissionConfig updated = rate("self_rate", "80");
        when(mapper.selectRatesForUpdate()).thenReturn(Collections.singletonList(rate("promotion_rate", "20")));
        when(mapper.updateSysCommissionConfig(updated)).thenReturn(1);
        assertEquals(1, service.updateSysCommissionConfig(updated));
    }

    @Test
    public void missingRatesFailInsteadOfReturningHiddenDefaults()
    {
        CommissionCalculateServiceImpl calculate = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(calculate, "commissionConfigMapper", mapper);
        assertThrows(ServiceException.class, calculate::getSelfCommissionRate);
        assertThrows(ServiceException.class, calculate::getPromotionCommissionRate);
    }
}
