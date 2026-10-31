package com.ruoyi.system.cps.settlement;

import com.ruoyi.system.cps.order.OrderEvent;

/** The settlement line supplies the event-to-rebate mapping. */
public interface RebateStatusMapper {
    RebateStatus map(OrderEvent event, RebateStatus current);
}
