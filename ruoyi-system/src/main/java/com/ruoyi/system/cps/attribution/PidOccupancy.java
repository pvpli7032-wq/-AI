package com.ruoyi.system.cps.attribution;
import com.ruoyi.union.adapter.Platform;
import java.math.BigDecimal;
public record PidOccupancy(Platform platform, String itemId, long occupied, long capacity, BigDecimal utilization) {}
