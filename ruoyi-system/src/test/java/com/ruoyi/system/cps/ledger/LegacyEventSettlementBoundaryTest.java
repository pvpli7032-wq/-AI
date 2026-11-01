package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Boundary-only tests; SQL locks and event settlement are covered separately in MySQL. */
public class LegacyEventSettlementBoundaryTest {
    @Test public void legacyAdminSettlementCannotBypassEventFreeze() {
        var mapper = mock(SysAgentCommissionAccountMapper.class);
        var service = service(mapper);
        assertThrows(ServiceException.class, () -> service.settleOrder(11L, new BigDecimal("10")));
        verify(mapper).selectOrderCommissionForUpdate(11L);
        verifyNoMoreInteractions(mapper);
    }
    @Test public void legacyFullRefundCannotBypassCumulativeRecovery() {
        var mapper = mock(SysAgentCommissionAccountMapper.class);
        var service = service(mapper);
        assertThrows(ServiceException.class, () -> service.refundOrder(11L));
        verify(mapper).selectOrderCommissionForUpdate(11L);
        verifyNoMoreInteractions(mapper);
    }
    private CommissionCalculateServiceImpl service(SysAgentCommissionAccountMapper mapper) {
        var order = new SysOrderCommission();
        order.setId(11L);
        order.setLineStatus(OrderStatus.SETTLED);
        when(mapper.selectOrderCommissionForUpdate(11L)).thenReturn(order);
        var service = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(service, "accountMapper", mapper);
        return service;
    }
}
