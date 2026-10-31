package com.ruoyi.system.cps.order;

import java.time.Instant;
import com.ruoyi.union.adapter.UnionOrderLine;

/** JSON is the actual upstream response, not a serialization of the normalized line. */
public record IncomingOrder(String rawJson, Instant fetchedAt, Instant allianceStatusTime, UnionOrderLine line,
        com.ruoyi.system.cps.contract.OrderStatus resolvedStatus, boolean restorePrevious, String source) {
    public IncomingOrder(String rawJson, Instant fetchedAt, Instant allianceStatusTime, UnionOrderLine line) {
        this(rawJson, fetchedAt, allianceStatusTime, line, null, false, "ALLIANCE");
    }
    /** Order-local interpretation, retained in the immutable replay envelope; does not change line-8 DTOs. */
    public IncomingOrder resolved(com.ruoyi.system.cps.contract.OrderStatus target, boolean restore, String origin) {
        return new IncomingOrder(rawJson, fetchedAt, allianceStatusTime, line, target, restore, origin);
    }
    /** The line-8 evidence is authoritative. Missing status time stays null. */
    public static IncomingOrder from(UnionOrderLine line, Instant fetchedAt) {
        if (line == null) throw new IllegalArgumentException("订单行不能为空");
        return new IncomingOrder(line.rawResponseJson(), fetchedAt, line.allianceStatusTime(), line);
    }

    @Override public String toString() {
        return "IncomingOrder[rawJson=<redacted>, fetchedAt=" + fetchedAt + ", allianceStatusTime=" + allianceStatusTime + "]";
    }

    public IncomingOrder {
        if (line != null && ((line.rawResponseJson() != null && !line.rawResponseJson().equals(rawJson))
            || (line.allianceStatusTime() != null && !line.allianceStatusTime().equals(allianceStatusTime))))
            throw new IllegalArgumentException("订单行证据与入站证据不一致");
    }
}
