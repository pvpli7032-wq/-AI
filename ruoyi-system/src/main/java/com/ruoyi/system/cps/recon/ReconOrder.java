package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;

public record ReconOrder(Long orderLineId, String platform, String orderNo, String subOrderNo,
    BigDecimal commission) {}
