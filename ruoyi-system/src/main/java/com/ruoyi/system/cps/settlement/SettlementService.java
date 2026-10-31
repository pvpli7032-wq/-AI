package com.ruoyi.system.cps.settlement;

import com.ruoyi.system.cps.order.OrderEvent;
public interface SettlementService { void onOrderEvent(OrderEvent event); }
