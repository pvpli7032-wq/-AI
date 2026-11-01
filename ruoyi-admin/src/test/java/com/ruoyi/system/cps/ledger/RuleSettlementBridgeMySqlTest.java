package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.contract.*;
import com.ruoyi.system.cps.mapper.*;
import com.ruoyi.system.cps.rule.*;
import com.ruoyi.system.cps.settlement.*;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;
import com.ruoyi.union.adapter.*;

/**
 * Real MySQL publication -> RuleEngine application service -> production bridge -> settlement,
 * RewardService and production ledger acceptance. RuleEngine is the rule service on this branch;
 * neither rule mapper nor settlement terms are faked. Requires the lead's LINE4_MYSQL_URL.
 * Reuses the migration harness's fresh UUID database, never starts/stops shared MySQL.
 * Each test gets a fresh migrated UUID database; the harness alone drops that database.
 * No enclosing test transaction masks the production event rollback assertions.
 *
 * All financial values below are SYNTHETIC TEST FIXTURES, not production rates/defaults.
 * Historical qualification and payment-invitation evidence are explicit test stubs:
 * line 9's actual historical evidence integration remains PENDING, not accepted by this suite.
 */
public class RuleSettlementBridgeMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(98_300_000L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RuleMapper drafts;
    private RuleVersionMapper versions;
    private RuleEngine rules;
    private SettlementConfigurationService configuration;
    private RuleSettlementTermsBridge bridge;
    private RewardService rewards;
    private DefaultSettlementService settlements;
    private long buyer, inviter;
    private boolean historicalUnknown;

    @After public void cleanup() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }
    @Before public void wireActualServices() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
        JdbcTemplate migrated = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
        jdbc = LedgerSqlTestClock.jdbc((org.springframework.jdbc.datasource.DriverManagerDataSource) migrated.getDataSource());
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        buyer = IDS.incrementAndGet(); inviter = IDS.incrementAndGet();
        user(buyer); user(inviter);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionFlowMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionConfigMapper.xml"));
        var sql = factory.getObject();
        sql.getConfiguration().addMapper(RuleMapper.class);
        sql.getConfiguration().addMapper(RuleVersionMapper.class);
        sql.getConfiguration().addMapper(RewardProgressMapper.class);
        var sessions = new SqlSessionTemplate(sql);
        drafts = sessions.getMapper(RuleMapper.class);
        versions = sessions.getMapper(RuleVersionMapper.class);
        // Explicit historical stub only. No current membership, agent application or graph lookup.
        RulePorts.Qualification pendingLine9 = (id, user, line, at) -> historicalUnknown
            ? RuleDecision.Evidence.unknown(at)
            : new RuleDecision.Evidence(at, "TEST-ONLY-LINE9-PENDING:" + id, 0L, bd("0"),
                0L, inviter, false, at.minusSeconds(86400), null, null, null, null, null);
        rules = transactional(new RuleEngine(drafts, versions, provider(RulePorts.Qualification.class, pendingLine9),
            provider(RulePorts.PublicationSafety.class, new RuleSafetyConfiguration(drafts))), RuleEngine.class);
        configuration = transactional(new SettlementConfigurationService(jdbc), SettlementConfigurationService.class);
        bridge = new RuleSettlementTermsBridge(rules, configuration);
        rewards = transactional(new RewardService(sessions.getMapper(RewardProgressMapper.class)), RewardService.class);
        var accounts = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts, "orderCommissionMapper", sessions.getMapper(SysOrderCommissionMapper.class));
        ReflectionTestUtils.setField(accounts, "flowMapper", sessions.getMapper(SysCommissionFlowMapper.class));
        ReflectionTestUtils.setField(accounts, "commissionConfigMapper", sessions.getMapper(SysCommissionConfigMapper.class));
        ReflectionTestUtils.setField(accounts, "accountMapper", sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", jdbc);
        LedgerService ledger = transactional(new DefaultLedgerService(accounts), LedgerService.class);
        PaymentInvitationSnapshotPort invitationProof = (platform, order, line, user, at) ->
            java.util.Optional.of(new PaymentInvitationSnapshotPort.Snapshot(user, inviter, at,
                "TEST-ONLY-INVITATION-LINE9-PENDING:" + order));
        var invitations = transactional(new PaymentInvitationSnapshotService(jdbc,
            provider(PaymentInvitationSnapshotPort.class, invitationProof)), PaymentInvitationSnapshotService.class);
        var notifications = transactional(new SettlementNotificationService(new SettlementNotificationRepository(jdbc),
            emptyProvider(SettlementNotificationChannel.class), manager), SettlementNotificationService.class);
        settlements = new DefaultSettlementService(new SettlementRepository(jdbc), ledger,
            provider(SettlementTermsPort.class, bridge),
            provider(SettlementFreezePort.class, new ConfiguredSettlementFreezePort()),
            new SettlementExternalServices(invitations, notifications), rewards,
            provider(SettlementRewardTermsPort.class, bridge), manager);
        // Explicit server commission cap; no margin floor is required any more.
        safety("20.00");
    }

    @Test public void explicitlyConfiguredZeroOrdinaryRateNeedsNeitherAgentNorHistoricalTierProof() {
        historicalUnknown = true;
        var publication = publish(policy("0", false));
        configureFreeze(3600);
        Instant paid = databaseNow();
        long id = order();
        settlements.onOrderEvent(event(id, "received", null, OrderStatus.RECEIVED, paid, "10", paid));
        var snapshot = JSON.parseObject(versions.snapshot(id), RuleDecision.Snapshot.class);
        assertEquals(publication.id(), snapshot.versionId());
        assertEquals("ordinary", snapshot.decision().proportionKey());
        money("0", snapshot.decision().userRate());
        assertNull(snapshot.evidence().validOrders());
        money("0", item(id, "REBATE", "final_amount"));
        money("0", balance(buyer, "frozen_amount"));
        assertEquals(0, flows(id));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?", id));
        assertEquals("RECEIVED", jdbc.queryForObject("SELECT order_status FROM cps_settlement_position WHERE order_line_id=?", String.class, id));
        // No agent application/approval fixture is inserted anywhere in this suite.
    }

    @Test public void negativeFullStackMarginIsPublishableAndStillFreezesAnImmutablePaymentSnapshot() {
        var unsafe = policy("0.80", true); // Can exceed net commission after the invitation leg.
        var publication = publish(unsafe);
        assertTrue(publication.config().contains("\"ordinary\""));
        assertFalse(publication.config().contains("minimumMargin"));
        configureFreeze(3600);
        long id = order();
        Instant paid = databaseNow();
        settlements.onOrderEvent(event(id, "paid", null, OrderStatus.PAID, paid, null, paid));
        String snapshotJson = versions.snapshot(id);
        assertNotNull(snapshotJson);
        var snapshot = JSON.parseObject(snapshotJson, RuleDecision.Snapshot.class);
        assertEquals(publication.id(), snapshot.versionId());
        assertEquals("ordinary", snapshot.decision().proportionKey());
        assertEquals(bd("0.80"), snapshot.decision().userRate());
        assertEquals(snapshotJson, versions.snapshot(id)); // idempotent persisted snapshot read
        assertEquals(1, rules.history(publication.documentId()).size());
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id IN (?,?)", buyer, inviter));
    }

    @Test public void delayedPaymentUsesImmutableOldPublicationAndPaymentTimeFinanceVersion() {
        var old = publish(policy("0.50", true));
        configureFreeze(3600);
        var oldFinance = configuration.history().getFirst();
        Instant paid = databaseNow();
        nextDatabaseMillisecond();
        var draft = drafts.byPlatform("TBK");
        draft = rules.update(draft.id(), draft.revision(), policy("0.10", false), buyer);
        var latest = rules.publish(draft.id(), draft.revision(), buyer);
        configureFreeze(7200);
        assertTrue(latest.effectiveAt().isAfter(paid));
        assertEquals(old.config(), rules.history(old.documentId()).get(1).config());
        assertTrue(old.safetyReference().contains("sys_config:"));
        long id = order(); // Ingestion occurs only AFTER replacement was published.
        Instant received = paid.plusSeconds(2);
        settlements.onOrderEvent(event(id, "delayed", null, OrderStatus.RECEIVED, paid, "10", received));
        var frozen = frozen(id);
        assertEquals(old.id(), frozen.rule().versionId());
        assertEquals(oldFinance.id(), frozen.configuration().id());
        assertEquals(oldFinance.effectiveAt(), frozen.configuration().effectiveAt());
        money("4.50", item(id, "REBATE", "final_amount"));
        money("2.25", item(id, "REFERRAL", "final_amount"));
        assertEquals(received.plusSeconds(3600), jdbc.queryForObject(
            "SELECT unfreeze_at FROM cps_settle_item WHERE order_line_id=? AND type='REBATE'", Timestamp.class, id).toInstant());
    }

    @Test public void referralUsesRebateBasisAndRewardServiceCannotPayTheSameReferralAgain() {
        var published = publish(policy("0.50", true));
        configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "received", null, OrderStatus.RECEIVED, paid, "10", paid));
        // 10 gross * (1-.10 service) * .50 buyer = 4.50; referral = 4.50*.50, NOT 10*.50.
        money("4.50", item(id, "REBATE", "final_amount"));
        money("2.25", item(id, "REFERRAL", "final_amount"));
        var frozen = frozen(id);
        assertEquals(List.of("referral"), frozen.rule().decision().incentiveKeys());
        assertEquals(published.id(), frozen.rule().versionId());
        var captured = JSON.parseObject(jdbc.queryForObject(
            "SELECT payload FROM cps_reward_order_snapshot WHERE order_id=?", String.class, id), RewardApi.Snapshot.class);
        assertTrue(captured.configs().isEmpty());
        settlements.unfreezeDue(paid.plusSeconds(3600), 1000);
        money("4.50", balance(buyer, "balance")); money("2.25", balance(inviter, "balance"));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=?", id));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE related_order_id=? AND flow_type='reward_credit'", id));
        long before = flows(id);
        settlements.unfreezeDue(paid.plusSeconds(7200), 1000);
        assertEquals(before, flows(id)); money("2.25", balance(inviter, "balance"));
    }

    @Test public void actualCommissionRepricingRetainsFullRuleAndFreezeConfigurationVersions() {
        var published = publish(policy("0.50", true)); configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "paid", null, OrderStatus.PAID, paid, null, paid));
        String originalTerms = termsJson(id), originalRule = versions.snapshot(id);
        var originalFrozen = frozen(id);
        nextDatabaseMillisecond();
        var draft = drafts.byPlatform("TBK");
        draft = rules.update(draft.id(), draft.revision(), policy("0.10", false), buyer);
        rules.publish(draft.id(), draft.revision(), buyer); configureFreeze(7200);
        historicalUnknown = true; // Repricing must not query new qualification either.
        Instant received = paid.plusSeconds(3);
        settlements.onOrderEvent(event(id, "received", OrderStatus.PAID, OrderStatus.RECEIVED, paid, "20", received));
        assertEquals(originalTerms, termsJson(id)); assertEquals(originalRule, versions.snapshot(id));
        assertEquals(originalFrozen, frozen(id));
        var actualTerms = JSON.parseObject(jdbc.queryForObject(
            "SELECT actual_terms_json FROM cps_settlement_position WHERE order_line_id=?", String.class, id),
            SettlementTermsPort.Terms.class);
        assertEquals(originalFrozen, JSON.parseObject(actualTerms.ruleSnapshotJson(), RuleSettlementTermsBridge.Frozen.class));
        assertEquals(JSON.parseObject(originalTerms, SettlementTermsPort.Terms.class).freezePolicy(), actualTerms.freezePolicy());
        assertNotEquals(originalFrozen.configuration().id(), configuration.history().getFirst().id());
        assertEquals(0, count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND rule_version_id<>?", id, published.id()));
        money("4.50", item(id, "REBATE", "est_amount")); money("9", item(id, "REBATE", "final_amount"));
        money("2.25", item(id, "REFERRAL", "est_amount")); money("4.50", item(id, "REFERRAL", "final_amount"));
        money("9", balance(buyer, "frozen_amount")); money("4.50", balance(inviter, "frozen_amount"));
        settlements.unfreezeDue(received.plusSeconds(3599), 1000); money("0", balance(buyer, "balance"));
        settlements.unfreezeDue(received.plusSeconds(3600), 1000); money("9", balance(buyer, "balance"));
        money("4.50", balance(inviter, "balance"));
    }

    @Test public void missingServerSafetyKeyFailsClosedOnPublication() {
        var draft = rules.create(policy("0.50", false), buyer);
        jdbc.update("DELETE FROM sys_config WHERE config_key=?", RuleSafetyConfiguration.PREFIX + "TBK.maximumActualCommission");
        assertTrue(assertThrows(IllegalStateException.class,
            () -> rules.publish(draft.id(), draft.revision(), buyer)).getMessage().contains("exactly one sys_config row"));
        assertTrue(rules.history(draft.id()).isEmpty());
    }

    @Test public void missingPaymentTimeFinanceConfigurationRollsBackRuleSnapshotAndAllMoney() {
        publish(policy("0.50", true)); // Deliberately no settlement finance publication.
        Instant paid = databaseNow(); long id = order();
        assertTrue(assertThrows(IllegalStateException.class, () -> settlements.onOrderEvent(
            event(id, "received", null, OrderStatus.RECEIVED, paid, "10", paid)))
            .getMessage().contains("No settlement configuration existed at payment"));
        assertNull(versions.snapshot(id));
        for (String table : List.of("cps_settlement_position", "cps_settlement_receipt", "cps_settle_item",
                "cps_payment_invitation_snapshot", "cps_settlement_notification"))
            assertEquals(table, 0, count("SELECT COUNT(*) FROM " + table + " WHERE order_line_id=?", id));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_reward_order_snapshot WHERE order_id=?", id));
        assertEquals(0, flows(id)); money("0", balance(buyer, "frozen_amount"));
    }

    // PR regressions only: all amounts/rates are synthetic, never rollout defaults.
    @Test public void expiredReferralWindowStaysIneligibleWhenHistoricalEvidenceLaterChanges() {
        var p = policy("0.50", true);
        var expired = new RulePolicy.Incentive("referral", RulePolicy.IncentiveKind.INVITATION_COMMISSION,
            bd("0"), bd("0.50"), 0, 1, null, null);
        publish(withIncentives(p, List.of(expired))); configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "paid", null, OrderStatus.PAID, paid, null, paid));
        String original = termsJson(id);
        assertTrue(frozen(id).rule().decision().incentiveKeys().isEmpty());
        historicalUnknown = true;
        settlements.onOrderEvent(event(id, "received", OrderStatus.PAID, OrderStatus.RECEIVED,
            paid, "20", paid.plusSeconds(1)));
        assertEquals(original, termsJson(id));
        money("9", item(id, "REBATE", "final_amount"));
        money("0", item(id, "REFERRAL", "final_amount"));
        money("0", balance(inviter, "frozen_amount"));
    }

    @Test public void selfPurchaseReferralRemainsRejectedAfterJsonRoundTripAndEvidenceReplacement() {
        publish(policy("0.50", true)); configureFreeze(3600);
        var qualified = bridgeWithEvidence((id, user, line, at) -> new RuleDecision.Evidence(at,
            "PR:SELF-PURCHASE", null, null, null, inviter, true,
            at.minusSeconds(60), null, null, null, null, null));
        Instant paid = databaseNow(); long id = order();
        var terms = qualified.snapshot(id, buyer, event(id, "paid", null, OrderStatus.PAID, paid, null, paid).line(), inviter);
        terms = JSON.parseObject(JSON.toJSONString(terms), SettlementTermsPort.Terms.class);
        assertTrue(JSON.parseObject(versions.snapshot(id), RuleDecision.Snapshot.class).decision().incentiveKeys().isEmpty());
        money("4.50", benefit(terms, "REBATE")); money("0", benefit(terms, "REFERRAL"));
        // The normal fixture would qualify referral now. Repricing must consume the stored rejection.
        var actual = bridge.recalculate(terms,
            event(id, "received", OrderStatus.PAID, OrderStatus.RECEIVED, paid, "20", paid.plusSeconds(1)).line());
        money("9", benefit(actual, "REBATE")); money("0", benefit(actual, "REFERRAL"));
        assertEquals(terms.ruleSnapshotJson(), actual.ruleSnapshotJson());
    }

    @Test public void unknownHistoricalEligibilityDoesNotSuppressOrdinaryBuyerOrInventReferral() {
        historicalUnknown = true;
        publish(policy("0.50", true)); configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "received", null, OrderStatus.RECEIVED, paid, "10", paid));
        assertTrue(frozen(id).rule().decision().incentiveKeys().isEmpty());
        money("4.50", item(id, "REBATE", "final_amount")); money("0", item(id, "REFERRAL", "final_amount"));
        money("4.50", balance(buyer, "frozen_amount")); money("0", balance(inviter, "frozen_amount"));
    }

    @Test public void knownReferralDoesNotRequireHistoricalOrderCounters() {
        publish(policy("0.50", true)); configureFreeze(3600);
        var qualified = bridgeWithEvidence((id, user, line, at) -> new RuleDecision.Evidence(at,
            "PR:KNOWN-INVITATION", null, null, null, inviter, false,
            at.minusSeconds(60), null, null, null, null, null));
        Instant paid = databaseNow(); long id = order();
        var terms = qualified.snapshot(id, buyer, event(id, "paid", null, OrderStatus.PAID, paid, null, paid).line(), inviter);
        assertEquals(List.of("referral"), JSON.parseObject(versions.snapshot(id), RuleDecision.Snapshot.class).decision().incentiveKeys());
        money("4.50", benefit(terms, "REBATE")); money("2.25", benefit(terms, "REFERRAL"));
    }

    @Test public void importedCountOverflowIsRejectedBeforePublicationForEveryCountAward() {
        var draft = rules.create(policy("0.50", false), buyer);
        for (var kind : List.of(RulePolicy.IncentiveKind.INVITATION_FIRST_N,
                RulePolicy.IncentiveKind.ORDER_COUNT, RulePolicy.IncentiveKind.FIRST_N_SUBSIDY)) {
            var invalid = withIncentives(policy("0.50", false), List.of(new RulePolicy.Incentive("overflow",
                kind, bd("0"), bd("0"), (long) Integer.MAX_VALUE + 1, 0, null, null)));
            assertThrows(IllegalArgumentException.class, () -> rules.create(invalid, buyer));
            // Import bypasses SAVE; PUBLISH must still validate before appending an immutable version.
            var current = drafts.byPlatform("TBK");
            assertEquals(1, drafts.replace(current.id(), current.revision(), JSON.toJSONString(invalid), false, buyer));
            assertEquals("reward N must be in [1,2147483647]", assertThrows(IllegalArgumentException.class,
                () -> rules.publish(current.id(), current.revision() + 1, buyer)).getMessage());
            assertTrue(rules.history(draft.id()).isEmpty());
        }
    }

    @Test public void forcedFirstUnusedPolicyNIsCanonicalizedBeforeRewardCaptureAndDbJsonReadback() {
        configureFreeze(3600);
        for (long unusedN : new long[] {0, 2, Long.MAX_VALUE}) {
            var p = withIncentives(policy("0.50", false), List.of(new RulePolicy.Incentive("forced",
                RulePolicy.IncentiveKind.FORCED_FIRST, bd("0"), bd("0"), unusedN, 5, null, null)));
            var current = drafts.byPlatform("TBK");
            var draft = current == null ? rules.create(p, buyer) : rules.update(current.id(), current.revision(), p, buyer);
            rules.publish(draft.id(), draft.revision(), buyer);
            Instant paid = databaseNow(); long id = order();
            var captured = bridge.snapshot(id, buyer, event(id, "paid", null, OrderStatus.PAID, paid, null, paid).line(), inviter, false);
            assertEquals(1, captured.configs().size());
            assertEquals(1, captured.configs().getFirst().n());
            assertEquals("forced", captured.configs().getFirst().key());
            new org.springframework.transaction.support.TransactionTemplate(manager)
                .executeWithoutResult(status -> rewards.capture(captured));
            assertEquals(captured, JSON.parseObject(jdbc.queryForObject(
                "SELECT payload FROM cps_reward_order_snapshot WHERE order_id=?", String.class, id), RewardApi.Snapshot.class));
        }
    }

    @Test public void persistedPlatformSubtotalIncludesFrozenOrdinaryPayoutCostOnActualRepricing() {
        var p = policy("0.50", true);
        publish(withPayoutCost(p, "0.10")); configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "paid", null, OrderStatus.PAID, paid, null, paid));
        // net 9 - ordinary 6.75 - rounded payout cost .68 = 1.57.
        money("1.57", JSON.parseObject(termsJson(id), SettlementTermsPort.Terms.class).platformAmount());
        nextDatabaseMillisecond();
        var draft = drafts.byPlatform("TBK");
        draft = rules.update(draft.id(), draft.revision(), withPayoutCost(p, "0"), buyer);
        rules.publish(draft.id(), draft.revision(), buyer);
        settlements.onOrderEvent(event(id, "received", OrderStatus.PAID, OrderStatus.RECEIVED,
            paid, "20", paid.plusSeconds(1)));
        // Old cost rate, actual commission: 18 - 13.50 - 1.35 = 3.15.
        money("3.15", jdbc.queryForObject("SELECT platform_amount FROM sys_order_commission WHERE id=?", BigDecimal.class, id));
        money("9", item(id, "REBATE", "final_amount")); money("4.50", item(id, "REFERRAL", "final_amount"));
    }

    @Test public void deferredAdvisoryAwardIsNotDeductedAsIfAlreadyPostedByOrdinaryBridge() {
        var p = policy("0.50", true);
        var subsidy = new RulePolicy.Incentive("deferred", RulePolicy.IncentiveKind.FIRST_N_SUBSIDY,
            bd("0"), bd("0.10"), 5, 0, null, null);
        publish(withPayoutCost(withIncentives(p, List.of(p.incentives().getFirst(), subsidy)), "0.10"));
        configureFreeze(3600); Instant paid = databaseNow(); long id = order();
        var terms = bridge.snapshot(id, buyer, event(id, "paid", null, OrderStatus.PAID, paid, null, paid).line(), inviter);
        var frozen = JSON.parseObject(terms.ruleSnapshotJson(), RuleSettlementTermsBridge.Frozen.class);
        var advisory = rules.calculate(frozen.rule(), bd("10"));
        money("1.08", advisory.margin()); // Includes .45 deferred subsidy and .72 full-stack cost.
        money("1.57", terms.platformAmount()); // Ordinary subtotal: excludes deferred principal + incremental cost.
        assertEquals(Set.of("REBATE", "REFERRAL"), terms.benefits().stream()
            .map(SettlementTermsPort.Benefit::type).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=?", id));
    }

    private RuleSettlementTermsBridge bridgeWithEvidence(RulePorts.Qualification evidence) {
        var qualified = transactional(new RuleEngine(drafts, versions, provider(RulePorts.Qualification.class, evidence),
            provider(RulePorts.PublicationSafety.class, new RuleSafetyConfiguration(drafts))), RuleEngine.class);
        return new RuleSettlementTermsBridge(qualified, configuration);
    }
    private RulePolicy withIncentives(RulePolicy p, List<RulePolicy.Incentive> incentives) {
        return new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), p.maximumCommission(),
            p.payoutCostRate(), p.minimumRebate(), p.targetedWithTier(), p.proportions(), incentives);
    }
    private RulePolicy withPayoutCost(RulePolicy p, String cost) {
        return new RulePolicy(p.platform(), p.serviceRate(), p.jdNonSelfRate(), p.maximumCommission(),
            bd(cost), p.minimumRebate(), p.targetedWithTier(), p.proportions(), p.incentives());
    }
    private BigDecimal benefit(SettlementTermsPort.Terms terms, String type) {
        return terms.benefits().stream().filter(b -> b.type().equals(type)).map(SettlementTermsPort.Benefit::amount)
            .reduce(bd("0"), BigDecimal::add);
    }

    private RulePolicy policy(String ordinary, boolean referral) {
        var target = new RulePolicy.Target(Set.of(), Set.of(), Set.of(), Set.of());
        var base = new RulePolicy.Proportion("ordinary", RulePolicy.Kind.BASE, RulePolicy.Mode.FIXED,
            bd(ordinary), target, 0, 0, null, List.of());
        var incentive = new RulePolicy.Incentive("referral", RulePolicy.IncentiveKind.INVITATION_COMMISSION,
            bd("0"), bd("0.50"), 0, 0, null, null);
        return new RulePolicy(Platform.TBK, bd("0.10"), bd("0"), bd("20"), bd("0"),
            bd("0"), false, List.of(base), referral ? List.of(incentive) : List.of());
    }
    private RuleDocuments.Version publish(RulePolicy policy) {
        var draft = rules.create(policy, buyer);
        return rules.publish(draft.id(), draft.revision(), buyer);
    }
    private void safety(String max) {
        jdbc.update("INSERT INTO sys_config(config_name,config_key,config_value,config_type) VALUES(?,?,?,'N')",
            "Synthetic rule bridge test ONLY", RuleSafetyConfiguration.PREFIX + "TBK.maximumActualCommission", max);
    }
    private void configureFreeze(long seconds) {
        var freeze = new ConfiguredSettlementFreezePort.Configuration(
            Map.of(Platform.TBK, List.of(new ConfiguredSettlementFreezePort.Band(bd("0"), seconds))),
            Map.of(Platform.TBK, seconds), false);
        configuration.publish(new SettlementConfigurationService.Settings(freeze, bd("0"), bd("0"), bd("20")), buyer);
    }
    private Instant databaseNow() {
        return LedgerSqlTestClock.now(jdbc);
    }
    private void nextDatabaseMillisecond() {
        // Publication timestamps are database-assigned milliseconds. No UPDATE/backdating immutable rows.
        jdbc.queryForObject("SELECT SLEEP(0.01)", Integer.class);
    }
    private void user(long id) {
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,?,'0','0')",
            id, "rule-bridge-" + id, "Synthetic bridge test");
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)", id, "rule-bridge-openid-" + id);
    }
    private long order() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,sub_order_no,platform_type,agent_user_id,buyer_user_id,order_amount,order_status) "
            + "VALUES (?,?,'line','tbk',?,?,100,'pending')", id, "rule-bridge-order-" + id, buyer, buyer);
        // Legacy NOT NULL owner column is not agent qualification; no agent record exists.
        return id;
    }
    private OrderEvent event(long id, String key, OrderStatus from, OrderStatus to, Instant paid, String actual, Instant at) {
        var line = new UnionOrderLine(Platform.TBK, "rule-bridge-order-" + id, "line", "item", "pid", null,
            bd("100"), bd("10"), actual == null ? null : bd(actual), "SYNTHETIC_TEST_PAYLOAD", paid,
            to == OrderStatus.PAID ? null : at, null);
        return new OrderEvent(id + ":" + key, id, line, from, to, null, null, "MYSQL_RULE_BRIDGE_TEST", id, at);
    }
    private String termsJson(long id) {
        return jdbc.queryForObject("SELECT terms_json FROM cps_settlement_position WHERE order_line_id=?", String.class, id);
    }
    private RuleSettlementTermsBridge.Frozen frozen(long id) {
        var terms = JSON.parseObject(termsJson(id), SettlementTermsPort.Terms.class);
        return JSON.parseObject(terms.ruleSnapshotJson(), RuleSettlementTermsBridge.Frozen.class);
    }
    private BigDecimal item(long id, String type, String field) {
        assertTrue(Set.of("est_amount", "final_amount").contains(field));
        return jdbc.queryForObject("SELECT " + field + " FROM cps_settle_item WHERE order_line_id=? AND type=?", BigDecimal.class, id, type);
    }
    private BigDecimal balance(long user, String field) {
        assertTrue(Set.of("balance", "frozen_amount").contains(field));
        var values = jdbc.queryForList("SELECT " + field + " FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user);
        return values.isEmpty() ? bd("0") : values.getFirst();
    }
    private long flows(long id) { return count("SELECT COUNT(*) FROM sys_commission_flow WHERE related_order_id=?", id); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static BigDecimal bd(String value) { return new BigDecimal(value).setScale(2); }
    private static void money(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(0, bd(expected).compareTo(actual)); }
    private <T> T transactional(T target, Class<T> type) {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target);
        if (type.isInterface()) proxy.setInterfaces(type); else proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice); return type.cast(proxy.getProxy());
    }
    private static <T> ObjectProvider<T> provider(Class<T> type, T value) {
        var beans = new StaticListableBeanFactory(); beans.addBean("explicit-test-provider", value);
        return beans.getBeanProvider(type);
    }
    private static <T> ObjectProvider<T> emptyProvider(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }
}
