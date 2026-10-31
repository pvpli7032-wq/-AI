package com.ruoyi.system.cps.settlement;

import java.time.Duration;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Pending adapter to the recovery colleague's FreezePolicy. No hard-coded duration. */
public interface SettlementFreezePort {
    Duration duration(SettlementTermsPort.Terms terms, UnionOrderLine line,
                      SettlementTermsPort.Benefit benefit);
}
