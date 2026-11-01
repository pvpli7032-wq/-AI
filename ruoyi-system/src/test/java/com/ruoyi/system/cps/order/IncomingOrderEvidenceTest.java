package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.Test;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import static org.junit.Assert.*;

public class IncomingOrderEvidenceTest {
    private UnionOrderLine line(String raw, Instant statusTime) {
        return new UnionOrderLine(Platform.TBK, "parent", "child", "item", "pid", null,
            BigDecimal.TEN, BigDecimal.ONE, null, "12", null, null, null, raw, statusTime);
    }
    @Test public void absentAllianceTimeNeverUsesFetchTime() {
        Instant fetched = Instant.parse("2026-01-01T00:00:00Z");
        IncomingOrder input = IncomingOrder.from(line("{ \"raw\": true }", null), fetched);
        assertNull(input.allianceStatusTime());
        assertNull(input.line().allianceStatusTime());
        assertEquals(fetched, input.fetchedAt());
        assertEquals("{ \"raw\": true }", input.rawJson());
    }
    @Test public void suppliedEvidenceCannotBeOverriddenByCaller() {
        Instant time = Instant.parse("2026-01-01T00:00:00Z");
        UnionOrderLine line = line("{}", time);
        assertThrows(IllegalArgumentException.class, () -> new IncomingOrder("[]", time, time, line));
        assertThrows(IllegalArgumentException.class, () -> new IncomingOrder("{}", time, time.plusSeconds(1), line));
        assertThrows(IllegalArgumentException.class, () -> new IncomingOrder("{}", time, null, line));
    }
    @Test public void oldConstructorRemainsCompatibleWithoutInventingEvidence() {
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, "parent", "child", null, null, null,
            BigDecimal.TEN, BigDecimal.ONE, null, "12", null, null, null);
        assertNull(IncomingOrder.from(line, Instant.EPOCH).rawJson());
        assertNull(IncomingOrder.from(line, Instant.EPOCH).allianceStatusTime());
    }
}
