package com.ruoyi.system.service.impl;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.domain.SysAgentCommissionAccount;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class SysAgentCommissionAccountStatusServiceTest
{
    private SysAgentCommissionAccountServiceImpl service;
    private SysAgentCommissionAccountMapper mapper;

    @Before
    public void setUp()
    {
        service = new SysAgentCommissionAccountServiceImpl();
        mapper = mock(SysAgentCommissionAccountMapper.class);
        ReflectionTestUtils.setField(service, "sysAgentCommissionAccountMapper", mapper);
    }

    @Test
    public void freezeChangesOnlyAccountStatus()
    {
        when(mapper.updateAccountStatus(42L, "1")).thenReturn(1);

        assertEquals(1, service.freezeAccount(42L));

        verify(mapper).updateAccountStatus(42L, "1");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    public void unfreezeChangesOnlyAccountStatus()
    {
        when(mapper.updateAccountStatus(42L, "0")).thenReturn(1);

        assertEquals(1, service.unfreezeAccount(42L));

        verify(mapper).updateAccountStatus(42L, "0");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    public void missingAccountDoesNotCreateAnAccount()
    {
        assertEquals(0, service.freezeAccount(404L));
        verify(mapper).updateAccountStatus(404L, "1");
        verifyNoMoreInteractions(mapper);
    }

    @Test
    public void invalidAccountIdsAreRejectedBeforeWriting()
    {
        for (Long id : Arrays.asList(null, 0L, -1L))
        {
            assertThrows(ServiceException.class, () -> service.freezeAccount(id));
            assertThrows(ServiceException.class, () -> service.unfreezeAccount(id));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    public void genericAccountUpdateRejectsEveryMonetaryField()
    {
        for (Consumer<SysAgentCommissionAccount> setter : monetarySetters(BigDecimal.ZERO))
        {
            SysAgentCommissionAccount account = new SysAgentCommissionAccount();
            account.setId(42L);
            setter.accept(account);
            assertThrows(ServiceException.class, () -> service.updateSysAgentCommissionAccount(account));
        }
        verifyNoInteractions(mapper);
    }

    @Test
    public void newAccountRejectsNonzeroAndNegativeMonetaryFields()
    {
        for (BigDecimal value : Arrays.asList(new BigDecimal("0.01"), new BigDecimal("-0.01")))
        {
            for (Consumer<SysAgentCommissionAccount> setter : monetarySetters(value))
            {
                SysAgentCommissionAccount account = new SysAgentCommissionAccount();
                account.setUserId(999001L);
                setter.accept(account);
                assertThrows(ServiceException.class, () -> service.insertSysAgentCommissionAccount(account));
            }
        }
        verifyNoInteractions(mapper);
    }

    @Test
    public void newAccountInitializesAllMoneyToZero()
    {
        SysAgentCommissionAccount account = new SysAgentCommissionAccount();
        account.setUserId(999001L);
        account.setBalance(new BigDecimal("0.00"));
        when(mapper.insertSysAgentCommissionAccount(account)).thenReturn(1);

        assertEquals(1, service.insertSysAgentCommissionAccount(account));

        assertEquals(BigDecimal.ZERO, account.getBalance());
        assertEquals(BigDecimal.ZERO, account.getFrozenAmount());
        assertEquals(BigDecimal.ZERO, account.getWithdrawn());
        assertEquals(BigDecimal.ZERO, account.getTotalEarned());
        assertEquals(BigDecimal.ZERO, account.getSelfEarned());
        assertEquals(BigDecimal.ZERO, account.getPromotionEarned());
        assertEquals("0", account.getStatus());
        verify(mapper).insertSysAgentCommissionAccount(account);
    }

    @Test
    public void genericAccountUpdateStillAllowsStatusChanges()
    {
        SysAgentCommissionAccount account = new SysAgentCommissionAccount();
        account.setId(42L);
        account.setStatus("1");
        when(mapper.updateSysAgentCommissionAccount(account)).thenReturn(1);

        assertEquals(1, service.updateSysAgentCommissionAccount(account));

        verify(mapper).updateSysAgentCommissionAccount(account);
    }

    private List<Consumer<SysAgentCommissionAccount>> monetarySetters(BigDecimal amount)
    {
        return Arrays.asList(account -> account.setBalance(amount), account -> account.setFrozenAmount(amount),
            account -> account.setWithdrawn(amount), account -> account.setTotalEarned(amount),
            account -> account.setSelfEarned(amount), account -> account.setPromotionEarned(amount));
    }
}
