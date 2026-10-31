package com.ruoyi.system.cps.order;

import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;

/** JSON is the actual upstream response, not a serialization of the normalized line. */
public record IncomingOrder(String rawJson, Instant fetchedAt, Instant allianceStatusTime, UnionOrderLine line) {}
