package com.ruoyi.system.cps.rule;

import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.session.Configuration;
import org.junit.Test;
import com.ruoyi.system.cps.mapper.RuleMapper;
import com.ruoyi.system.cps.mapper.RuleVersionMapper;
import static org.junit.Assert.*;

/** SQL binding smoke tests only; no claim of MySQL execution/locking verification. */
public class RuleMapperSqlTest {
    @Test public void annotationStatementsParseAndBindPaidTimeRatherThanCurrentState() {
        Configuration c = new Configuration();
        c.addMapper(RuleMapper.class); c.addMapper(RuleVersionMapper.class);
        var statement = c.getMappedStatement(RuleVersionMapper.class.getName() + ".asOf");
        var sql = statement.getBoundSql(Map.of("platform", "TBK", "paidAt", Instant.EPOCH));
        assertTrue(sql.getSql().contains("v.effective_at <= ?"));
        assertTrue(sql.getSql().contains("ORDER BY v.effective_at DESC,v.id DESC"));
        assertFalse(sql.getSql().contains("archived"));
        assertEquals("paidAt", sql.getParameterMappings().get(1).getProperty());
        assertEquals(RuleDocuments.Version.class, statement.getResultMaps().getFirst().getType());
    }
    @Test public void snapshotRaceReadsAreLockingAndPublishedRowsHaveNoMutationStatements() {
        Configuration c = new Configuration(); c.addMapper(RuleVersionMapper.class);
        var name = RuleVersionMapper.class.getName();
        assertTrue(c.getMappedStatement(name + ".lockedSnapshot").getBoundSql(1L).getSql().endsWith("FOR UPDATE"));
        assertFalse(c.hasStatement(name + ".update"));
        assertFalse(c.hasStatement(name + ".delete"));
    }
}
