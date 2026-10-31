package com.ruoyi.system.cps.risk;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import org.junit.Test;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysConfigService;

public class RiskPolicyTest {
    @Test
    public void zeroIsAnExplicitDisableAndMissingValuesAreNotDefaults() {
        ISysConfigService configs = mock(ISysConfigService.class);
        when(configs.selectConfigByKey("configured-zero")).thenReturn("0");
        RiskPolicy policy = new RiskPolicy(configs);
        assertEquals(0, policy.count("configured-zero"));
        assertEquals(BigDecimal.ZERO, policy.decimal("configured-zero"));
        assertThrows(ServiceException.class, () -> policy.count("missing"));
    }

    @Test
    public void malformedFractionalAndOverflowCountsCannotEnableAnUnintendedRule() {
        ISysConfigService configs = mock(ISysConfigService.class);
        RiskPolicy policy = new RiskPolicy(configs);
        for (String value : new String[] {"-1", "1.5", "NaN", "9223372036854775808"}) {
            when(configs.selectConfigByKey("count")).thenReturn(value);
            assertThrows(ServiceException.class, () -> policy.count("count"));
        }
    }
}
