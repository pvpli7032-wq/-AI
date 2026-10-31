package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.mapper.RuleMapper;
import com.ruoyi.system.cps.mapper.RuleVersionMapper;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import static com.ruoyi.system.cps.rule.RuleEvaluationTest.*;
import static com.ruoyi.system.cps.rule.RuleDocuments.*;
import static com.ruoyi.system.cps.rule.RuleDecision.*;
import static org.junit.Assert.*;

/** Application boundary tests with memory persistence, NOT substitutes for MySQL locking tests. */
public class RuleEnginePersistenceTest {
    static class Drafts implements RuleMapper {
        Draft draft;
        public int create(String platform, String config, long operator) {
            draft = new Draft(1, platform, 1, config, false); return 1;
        }
        public Draft byPlatform(String platform) { return draft; }
        public Draft lock(long id) { return draft; }
        public List<Draft> list() { return draft == null ? List.of() : List.of(draft); }
        public int replace(long id, long revision, String config, boolean archived, long operator) {
            if (draft.revision() != revision) return 0;
            draft = new Draft(id, draft.platform(), revision + 1, config, archived); return 1;
        }
    }
    static class Versions implements RuleVersionMapper {
        final List<Version> rows = new ArrayList<>();
        final HashMap<Long, String> snapshots = new HashMap<>();
        Instant requestedAsOf;
        int lookups;
        public int append(long id, long rev, String config, boolean enabled, long operator, String safety) {
            rows.addFirst(new Version(rows.size() + 1, id, rev, config, enabled, AT.minusSeconds(100), operator, safety)); return 1;
        }
        public Version asOf(String platform, Instant at) {
            requestedAsOf = at; lookups++;
            return rows.stream().filter(v -> !v.effectiveAt().isAfter(at)).findFirst().orElse(null);
        }
        public List<Version> history(long id) { return List.copyOf(rows); }
        public String snapshot(long id) { return snapshots.get(id); }
        public String lockedSnapshot(long id) { return snapshots.get(id); }
        public int insertSnapshot(long id, long buyer, long version, String snapshot) { snapshots.putIfAbsent(id, snapshot); return 1; }
    }
    static RuleEngine engine(Drafts drafts, Versions versions, RulePorts.PublicationSafety safety) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (safety != null) factory.addBean("safety", safety);
        return new RuleEngine(drafts, versions, factory.getBeanProvider(RulePorts.Qualification.class),
            factory.getBeanProvider(RulePorts.PublicationSafety.class));
    }
    static RulePorts.PublicationSafety safe() {
        return p -> new RulePorts.Limits(d("10"), d("-1"), true, "server-finance:test-only");
    }
    static UnionOrderLine line() {
        return new UnionOrderLine(Platform.TBK, "o", "s", "sku", null, null, d("5"), d("2"), d("2"), "paid", AT, null, null);
    }
    @Test public void firstSnapshotUsesPaidAtAndReusesFullPersistedConfiguration() {
        Drafts drafts = new Drafts(); Versions versions = new Versions(); RuleEngine engine = engine(drafts, versions, safe());
        Draft draft = engine.create(policy(List.of(base("0.2"))), 3);
        engine.publish(draft.id(), draft.revision(), 3);
        Snapshot first = engine.snapshotFor(7, 9, line());
        assertEquals(AT, versions.requestedAsOf);
        Draft changed = engine.update(1, 1, policy(List.of(base("0.8"))), 3);
        engine.publish(1, changed.revision(), 3);
        assertEquals(first, engine.snapshotFor(7, 9, line()));
        assertEquals(1, versions.lookups);
        assertEquals(d("0.90"), engine.calculate(first, d("5")).rebate());
        assertThrows(IllegalStateException.class, () -> engine.snapshotFor(7, 10, line()));
    }
    @Test public void cannotSettleUnpersistedOrForgedSnapshot() {
        Drafts drafts = new Drafts(); Versions versions = new Versions(); RuleEngine engine = engine(drafts, versions, safe());
        engine.create(policy(List.of(base("0.2"))), 3); engine.publish(1, 1, 3);
        Snapshot first = engine.snapshotFor(7, 9, line());
        Snapshot forged = new Snapshot(first.orderLineId(), 9, first.platform(), first.orderNo(), first.subOrderNo(), first.itemId(),
            first.paidAt(), first.versionId(), first.policy(), first.evidence(), new RuleDecision("base", d("1"), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(forged, d("5")));
        versions.snapshots.clear();
        assertThrows(IllegalArgumentException.class, () -> engine.calculate(first, d("5")));
    }
    @Test public void serverSafetyIsRequiredAndClientCannotEnlargeCommissionCap() {
        Drafts drafts = new Drafts(); Versions versions = new Versions();
        assertThrows(IllegalStateException.class, () -> engine(drafts, versions, null).create(policy(List.of(base("0.2"))), 3));
        RuleEngine limited = engine(drafts, versions, p -> new RulePorts.Limits(d("1"), d("-1"), true, "server"));
        assertThrows(IllegalArgumentException.class, () -> limited.create(policy(List.of(base("0.2"))), 3));
        RuleEngine rewardEnabled = engine(drafts, versions, p -> new RulePorts.Limits(d("10"), d("-1"), false, "server"));
        assertThrows(IllegalStateException.class, () -> rewardEnabled.create(policy(List.of(base("0.2"))), 3));
    }
    @Test public void deletePreservesHistoryAndPreviouslyFrozenSnapshot() {
        Drafts drafts = new Drafts(); Versions versions = new Versions(); RuleEngine engine = engine(drafts, versions, safe());
        engine.create(policy(List.of(base("0.2"))), 3); engine.publish(1, 1, 3);
        Snapshot first = engine.snapshotFor(7, 9, line());
        engine.delete(1, 1, 3);
        assertTrue(drafts.draft.archived()); assertEquals(2, engine.history(1).size());
        assertEquals(d("0.90"), engine.calculate(first, d("5")).rebate());
        assertThrows(IllegalStateException.class, () -> engine.snapshotFor(8, 9, line()));
    }
    @Test public void revisionConflictAndMissingPaidAtPolicyFailClosed() {
        Drafts drafts = new Drafts(); Versions versions = new Versions(); RuleEngine engine = engine(drafts, versions, safe());
        engine.create(policy(List.of(base("0.2"))), 3);
        assertThrows(IllegalStateException.class, () -> engine.update(1, 5, policy(List.of(base("0.5"))), 3));
        assertThrows(IllegalStateException.class, () -> engine.snapshotFor(1, 9, line()));
        assertEquals(0, versions.snapshots.size());
    }
}
