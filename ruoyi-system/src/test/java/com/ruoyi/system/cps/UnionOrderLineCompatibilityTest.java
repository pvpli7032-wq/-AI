package com.ruoyi.system.cps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class UnionOrderLineCompatibilityTest {
    @Test public void legacyConstructorRemainsUsableAndDoesNotInventEvidence() {
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, "order", "sub", "item", "pid", "token",
            new BigDecimal("12.34"), null, null, "12", null, null, null);
        assertEquals("order", line.orderNo());
        assertEquals(new BigDecimal("12.34"), line.paidAmount());
        assertNull(line.rawResponseJson());
        assertNull(line.allianceStatusTime());
        assertFalse(line.toString().contains("token"));
    }

    @Test public void preservesEvidenceWithoutLosingPrecisionAndReadsLegacyJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UnionOrderLine old = mapper.readValue("{\"platform\":\"TBK\",\"orderNo\":\"old\",\"subOrderNo\":\"\"}", UnionOrderLine.class);
        assertNull(old.rawResponseJson());
        assertNull(old.allianceStatusTime());
        String raw = "{ \"synthetic\": true,\n  \"id\": 7 }";
        Instant updated = Instant.parse("2026-01-02T03:04:05.987654321Z");
        UnionOrderLine line = new UnionOrderLine(Platform.JD, "order", "sub", null, null, "secret-token",
            null, null, null, "status", null, null, null, raw, updated);
        assertEquals(raw, line.rawResponseJson());
        assertEquals(updated, line.allianceStatusTime());
        assertFalse(line.toString().contains(raw));
        assertFalse(line.toString().contains("secret-token"));
    }
}
