package com.ruoyi.system.cps.settlement;

import com.ruoyi.system.cps.contract.OrderEvent;
public interface SettlementService { void onOrderEvent(OrderEvent event); }
