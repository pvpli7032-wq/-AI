package com.ruoyi.system.cps.settlement;

import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.RebateStatus;

/** The settlement line supplies the event-to-rebate mapping. */
public interface RebateStatusMapper {
    RebateStatus map(OrderEvent event, RebateStatus current);
}
