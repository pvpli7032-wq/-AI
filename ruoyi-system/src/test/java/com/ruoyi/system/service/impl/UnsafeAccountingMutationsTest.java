package com.ruoyi.system.service.impl;

import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.domain.SysCommissionFlow;
import com.ruoyi.system.domain.SysOrderCommission;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.mapper.SysCommissionFlowMapper;
import com.ruoyi.system.mapper.SysOrderCommissionMapper;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

public class UnsafeAccountingMutationsTest
{
    @Test
    public void accountOwnerAndDeletionCannotBeChangedDirectly()
    {
        SysAgentCommissionAccountMapper mapper = mock(SysAgentCommissionAccountMapper.class);
        SysAgentCommissionAccountServiceImpl service = new SysAgentCommissionAccountServiceImpl();
        ReflectionTestUtils.setField(service, "sysAgentCommissionAccountMapper", mapper);
        SysAgentCommissionAccount account = new SysAgentCommissionAccount();
        account.setId(12L);
        account.setUserId(34L);
        assertThrows(ServiceException.class, () -> service.updateSysAgentCommissionAccount(account));
        assertThrows(ServiceException.class, () -> service.deleteSysAgentCommissionAccountById(12L));
        assertThrows(ServiceException.class, () -> service.deleteSysAgentCommissionAccountByIds(new Long[] {12L}));
        verifyNoInteractions(mapper);
    }

    @Test
    public void flowInsertionCannotBypassUnifiedAccounting()
    {
        SysCommissionFlowMapper mapper = mock(SysCommissionFlowMapper.class);
        SysCommissionFlowServiceImpl service = new SysCommissionFlowServiceImpl();
        ReflectionTestUtils.setField(service, "sysCommissionFlowMapper", mapper);
        assertThrows(ServiceException.class, () -> service.insertSysCommissionFlow(new SysCommissionFlow()));
        verifyNoInteractions(mapper);
    }

    @Test
    public void orderMutationCannotBypassAccounting()
    {
        SysOrderCommissionMapper mapper = mock(SysOrderCommissionMapper.class);
        SysOrderCommissionServiceImpl service = new SysOrderCommissionServiceImpl();
        ReflectionTestUtils.setField(service, "sysOrderCommissionMapper", mapper);
        assertThrows(ServiceException.class, () -> service.updateSysOrderCommission(new SysOrderCommission()));
        assertThrows(ServiceException.class, () -> service.deleteSysOrderCommissionById(12L));
        assertThrows(ServiceException.class, () -> service.deleteSysOrderCommissionByIds(new Long[] {12L}));
        verifyNoInteractions(mapper);
    }
}
