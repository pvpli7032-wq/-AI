package com.ruoyi.system.cps.rule;

import java.util.List;
import org.junit.Test;
import static com.ruoyi.system.cps.rule.RuleEvaluationTest.*;
import static org.junit.Assert.*;

/** sys_config adapter contract tests, not database integration tests. No real rates. */
public class RuleSafetyConfigurationTest {
    static String max() { return "cps.rule.safety.TBK.maximumActualCommission"; }
    static String min() { return "cps.rule.safety.TBK.minimumMargin"; }
    static RuleSafetyConfiguration.Entry row(long id, String key, String value) {
        return new RuleSafetyConfiguration.Entry(id, key, value);
    }
    @Test public void explicitNegativeMarginIsAllowedAndAuditIdentifiesSourceValues() {
        var mapper = new RuleEnginePersistenceTest.Drafts();
        mapper.limits = List.of(row(1, max(), "10.00"), row(2, min(), "-2.00"));
        var limits = new RuleSafetyConfiguration(mapper).limits(policy(List.of(base("0"))));
        assertEquals(d("10.00"), limits.maximumActualCommission()); assertEquals(d("-2.00"), limits.minimumMargin());
        assertTrue(limits.sourceReference().contains(max() + "=10.00"));
        assertTrue(limits.sourceReference().contains("sys_config:1:"));
    }
    @Test public void absentDuplicateAndCaseAmbiguousRowsFailClosed() {
        var mapper = new RuleEnginePersistenceTest.Drafts(); var adapter = new RuleSafetyConfiguration(mapper);
        var p = policy(List.of(base("0")));
        assertThrows(IllegalStateException.class, () -> adapter.limits(p));
        mapper.limits = List.of(row(1, max(), "10"), row(2, max(), "10"));
        assertThrows(IllegalStateException.class, () -> adapter.limits(p));
        mapper.limits = List.of(row(1, max(), "10"), row(2, min(), "0"), row(3, max(), "10"));
        assertThrows(IllegalStateException.class, () -> adapter.limits(p));
        mapper.limits = List.of(row(1, max().toUpperCase(), "10"), row(2, min(), "0"));
        assertThrows(IllegalStateException.class, () -> adapter.limits(p));
    }
    @Test public void malformedFractionalOrUnboundedServerValuesFailClosed() {
        var mapper = new RuleEnginePersistenceTest.Drafts(); var adapter = new RuleSafetyConfiguration(mapper);
        for (String invalid : List.of("", "NaN", "1e2", " 10", "0", "-1", "1.001", "10000.01")) {
            mapper.limits = List.of(row(1, max(), invalid), row(2, min(), "-1"));
            assertThrows(invalid, IllegalStateException.class, () -> adapter.limits(policy(List.of(base("0")))));
        }
    }
    @Test public void paidTimeIncentivesAndEvidenceRemainFrozenAfterPolicyChanges() {
        var drafts = new RuleEnginePersistenceTest.Drafts(); var versions = new RuleEnginePersistenceTest.Versions();
        drafts.limits = List.of(row(1, max(), "10"), row(2, min(), "-2"));
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        factory.addBean("safety", new RuleSafetyConfiguration(drafts));
        factory.addBean("qualification", (RulePorts.Qualification) (id, buyer, line, at) -> RuleIncentiveTest.prior(0, 9L));
        var engine = new RuleEngine(drafts, versions, factory.getBeanProvider(RulePorts.Qualification.class),
            factory.getBeanProvider(RulePorts.PublicationSafety.class));
        engine.create(RuleIncentiveTest.configured(RuleIncentiveTest.all(), "-2"), 3); engine.publish(1, 1, 3);
        var snapshot = engine.snapshotFor(7, 7, RuleEnginePersistenceTest.line());
        assertEquals(6, snapshot.decision().incentiveKeys().size());
        engine.update(1, 1, policy(List.of(base("0"))), 3); engine.publish(1, 2, 3);
        assertEquals(snapshot, engine.snapshotFor(7, 7, RuleEnginePersistenceTest.line()));
        assertEquals(6, engine.calculate(snapshot, d("2")).rewards().size());
    }
    @Test public void ordinaryBuyerWithNoHistoricalProviderStillGetsBaseAndFrozenReview() {
        var drafts = new RuleEnginePersistenceTest.Drafts(); var versions = new RuleEnginePersistenceTest.Versions();
        drafts.limits = List.of(row(1, max(), "10"), row(2, min(), "-2"));
        var engine = RuleEnginePersistenceTest.engine(drafts, versions, new RuleSafetyConfiguration(drafts));
        engine.create(RuleIncentiveTest.configured(RuleIncentiveTest.all(), "-2"), 3); engine.publish(1, 1, 3);
        var snapshot = engine.snapshotFor(7, 9, RuleEnginePersistenceTest.line());
        assertEquals(6, snapshot.decision().manualReviewReasons().size());
        assertEquals(d("0.90"), engine.calculate(snapshot, d("2")).rebate());
        assertTrue(engine.calculate(snapshot, d("2")).rewards().isEmpty());
    }
    @Test public void enginePublishesStackedPolicyUsingServerAdapterNotClientCostAndRechecksOnPublish() {
        var drafts = new RuleEnginePersistenceTest.Drafts(); var versions = new RuleEnginePersistenceTest.Versions();
        drafts.limits = List.of(row(1, max(), "10"), row(2, min(), "-2"));
        var engine = RuleEnginePersistenceTest.engine(drafts, versions, new RuleSafetyConfiguration(drafts));
        var p = RuleIncentiveTest.configured(RuleIncentiveTest.all(), "-2");
        engine.create(p, 3); assertNotNull(engine.publish(1, 1, 3));
        drafts.limits = List.of(row(1, max(), "10"), row(2, min(), "0"));
        assertThrows(IllegalArgumentException.class, () -> engine.publish(1, 1, 3));
    }
}
