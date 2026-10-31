package com.ruoyi.system.cps.order;

import com.ruoyi.union.adapter.Platform;
/** Platform-specific implementations are provided by the order line. */
public interface OrderStatusMapper { OrderStatus map(Platform platform, String rawStatus); }
