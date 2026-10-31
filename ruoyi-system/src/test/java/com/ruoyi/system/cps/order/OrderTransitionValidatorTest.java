package com.ruoyi.system.cps.order;

import org.junit.Test;
import com.ruoyi.system.cps.contract.OrderStatus;
import static com.ruoyi.system.cps.order.OrderTransitionValidator.Outcome.*;
import static org.junit.Assert.*;

public class OrderTransitionValidatorTest
{
    private final OrderTransitionValidator validator = new OrderTransitionValidator();

    @Test
    public void enforcesContractAndDoesNotSkipIntermediateState()
    {
        assertEquals(INITIAL, validator.evaluate(null, OrderStatus.PAID, null).outcome());
        assertEquals(ILLEGAL, validator.evaluate(null, OrderStatus.SETTLED, null).outcome());
        assertEquals(APPLY, validator.evaluate(OrderStatus.PAID, OrderStatus.RECEIVED, null).outcome());
        assertEquals(APPLY, validator.evaluate(OrderStatus.RECEIVED, OrderStatus.SETTLED, null).outcome());
        assertEquals(APPLY, validator.evaluate(OrderStatus.PAID, OrderStatus.SETTLED, null).outcome());
        assertEquals(ILLEGAL, validator.evaluate(OrderStatus.SETTLED, OrderStatus.PAID, null).outcome());
        assertEquals(DUPLICATE, validator.evaluate(OrderStatus.RECEIVED, OrderStatus.RECEIVED, null).outcome());
        assertFalse(validator.evaluate(OrderStatus.INVALID, OrderStatus.PAID, null).reason().isEmpty());
    }

    @Test
    public void disputeRestoresOnlyRecordedPreviousStatus()
    {
        assertEquals(APPLY, validator.evaluate(OrderStatus.SETTLED, OrderStatus.DISPUTED, null).outcome());
        assertEquals(APPLY, validator.evaluate(OrderStatus.DISPUTED, OrderStatus.SETTLED, OrderStatus.SETTLED).outcome());
        assertEquals(ILLEGAL, validator.evaluate(OrderStatus.DISPUTED, OrderStatus.RECEIVED, OrderStatus.SETTLED).outcome());
        assertEquals(ILLEGAL, validator.evaluate(OrderStatus.DISPUTED, OrderStatus.RECEIVED, null).outcome());
        assertEquals(APPLY, validator.evaluate(OrderStatus.DISPUTED, OrderStatus.FULL_REFUND, OrderStatus.RECEIVED).outcome());
        assertEquals(APPLY, validator.evaluate(OrderStatus.DISPUTED, OrderStatus.PARTIAL_REFUND, OrderStatus.SETTLED).outcome());
        assertEquals(ILLEGAL, validator.evaluate(OrderStatus.DISPUTED, OrderStatus.PARTIAL_REFUND, OrderStatus.PAID).outcome());
    }
}
