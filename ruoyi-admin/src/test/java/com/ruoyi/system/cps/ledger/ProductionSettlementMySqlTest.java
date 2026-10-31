package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
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
 * Publication -> immutable production terms -> onOrderEvent -> actual MyBatis ledger/account.
 * RuleEngine is this branch's rule application service (there is no separate RuleService API).
 * All financial rates, limits and durations are SYNTHETIC TEST FIXTURES, not business defaults.
 * Only payment-invitation evidence and historical qualification are stubbed: line 9's historical
 * integration remains PENDING. Neither financial terms, freeze configuration nor ledger is stubbed.
 * Requires the lead-owned LINE4_MYSQL_URL/MySQL 8; never starts or stops a server. The existing
 * harness applies ALL real Flyway migrations to a fresh UUID schema per test and drops only it.
 * No enclosing test transaction hides the service's actual commits. Notification outbox is real;
 * external channel delivery is not claimed. Conflicting current inviters are monetary canaries,
 * not SQL-query tracing instrumentation.
 */
public class ProductionSettlementMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(98_700_000L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private RuleMapper drafts;
    private RuleVersionMapper versions;
    private RuleEngine rules;
    private SettlementConfigurationService configuration;
    private DefaultSettlementService settlements;
    private long buyer, inviter, currentInviter;
    private boolean unknownHistory, forbidEvidenceRead;
    private int invitationReads, qualificationReads;

    @Before public void wireProductionServices() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
        var migratedJdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class, "jdbc");
        jdbc = fixtureJdbc((DriverManagerDataSource) migratedJdbc.getDataSource());
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
        buyer = IDS.incrementAndGet(); inviter = IDS.incrementAndGet(); currentInviter = IDS.incrementAndGet();
        user(buyer); user(inviter); user(currentInviter);
        // Present-day graph deliberately contradicts the authenticated payment-time evidence.
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=? WHERE user_id=?", currentInviter, buyer);
        assertEquals(0, count("SELECT COUNT(*) FROM sys_tbk_agent_apply WHERE user_id IN (?,?,?)",
            buyer, inviter, currentInviter));
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
        RulePorts.Qualification pendingLine9 = (id, user, line, at) -> {
            assertFalse("immutable orders must not reread historical qualification", forbidEvidenceRead);
            qualificationReads++;
            assertEquals(buyer, user); assertEquals(line.paidAt(), at);
            return unknownHistory ? RuleDecision.Evidence.unknown(at)
                : new RuleDecision.Evidence(at, "TEST-ONLY-LINE9-PENDING:" + id, 0L, bd("0"),
                    0L, inviter, false, at.minusSeconds(86400), null, null, null, null, null);
        };
        rules = transactional(new RuleEngine(drafts, versions, provider(RulePorts.Qualification.class, pendingLine9),
            provider(RulePorts.PublicationSafety.class, new RuleSafetyConfiguration(drafts))), RuleEngine.class);
        configuration = transactional(new SettlementConfigurationService(jdbc), SettlementConfigurationService.class);
        var bridge = new RuleSettlementTermsBridge(rules, configuration);
        var rewards = transactional(new RewardService(sessions.getMapper(RewardProgressMapper.class)), RewardService.class);
        var accounts = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts, "orderCommissionMapper", sessions.getMapper(SysOrderCommissionMapper.class));
        ReflectionTestUtils.setField(accounts, "flowMapper", sessions.getMapper(SysCommissionFlowMapper.class));
        ReflectionTestUtils.setField(accounts, "commissionConfigMapper", sessions.getMapper(SysCommissionConfigMapper.class));
        ReflectionTestUtils.setField(accounts, "accountMapper", sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts, "ledgerJdbc", jdbc);
        LedgerService ledger = transactional(new DefaultLedgerService(accounts), LedgerService.class);
        PaymentInvitationSnapshotPort pendingInvitationLine9 = (platform, order, line, user, paid) -> {
            assertFalse("immutable orders must not reread payment invitation", forbidEvidenceRead);
            invitationReads++;
            assertEquals(Long.valueOf(buyer), user);
            return unknownHistory ? Optional.empty() : Optional.of(new PaymentInvitationSnapshotPort.Snapshot(
                user, inviter, paid, "TEST-ONLY-INVITATION-LINE9-PENDING:" + order));
        };
        var invitations = transactional(new PaymentInvitationSnapshotService(jdbc,
            provider(PaymentInvitationSnapshotPort.class, pendingInvitationLine9)), PaymentInvitationSnapshotService.class);
        var notifications = transactional(new SettlementNotificationService(new SettlementNotificationRepository(jdbc),
            new StaticListableBeanFactory().getBeanProvider(SettlementNotificationChannel.class), manager),
            SettlementNotificationService.class);
        settlements = new DefaultSettlementService(new SettlementRepository(jdbc), ledger,
            provider(SettlementTermsPort.class, bridge),
            provider(SettlementFreezePort.class, new ConfiguredSettlementFreezePort()),
            new SettlementExternalServices(invitations, notifications), rewards,
            provider(SettlementRewardTermsPort.class, bridge), manager);
        // Explicit server-side publication safety, including a one-cent rounded-loss allowance.
        for (var entry : Map.of("maximumActualCommission", "20.00", "minimumMargin", "-0.01").entrySet())
            jdbc.update("INSERT INTO sys_config(config_name,config_key,config_value,config_type) VALUES(?,?,?,'N')",
                "SYNTHETIC production settlement test ONLY", RuleSafetyConfiguration.PREFIX + "TBK." + entry.getKey(), entry.getValue());
    }

    @After public void dropOnlyIsolatedSchema() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }

    @Test public void ordinaryNonAgentFreezesReleasesAndRecoversCumulativelyFromOriginalInviter() {
        var published = publish(); configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        var received = event(id, "received", null, OrderStatus.RECEIVED, paid, "10", null, paid);
        settlements.onOrderEvent(received);
        assertEquals("ordinary", frozen(id).rule().decision().proportionKey());
        assertEquals(published.id(), frozen(id).rule().versionId());
        assertEquals(Long.valueOf(inviter), frozen(id).inviterUserId());
        assertEquals(Long.valueOf(inviter), jdbc.queryForObject(
            "SELECT inviter_user_id FROM cps_payment_invitation_snapshot WHERE order_line_id=?", Long.class, id));
        // 10 * (1 - .10 service) * .50 buyer = 4.50; referral is .50 OF REBATE = 2.25.
        money("4.50", item(id, "REBATE", "final_amount")); money("2.25", item(id, "REFERRAL", "final_amount"));
        money("2.25", jdbc.queryForObject("SELECT platform_amount FROM sys_order_commission WHERE id=?", BigDecimal.class, id));
        money("4.50", balance(buyer, "frozen_amount")); money("2.25", balance(inviter, "frozen_amount"));
        posted(id, buyer, "REBATE_COST", "USER_FROZEN", "4.50");
        posted(id, inviter, "REWARD_COST", "USER_FROZEN", "2.25");
        assertEquals(2, flows(id)); audit(buyer); audit(inviter);
        assertTrue(rewardSnapshot(id).configs().isEmpty()); // Referral is owned only by settlement.
        forbidEvidenceRead = true;
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=NULL WHERE user_id=?", buyer);
        settlements.onOrderEvent(received); assertEquals(2, flows(id));
        settlements.unfreezeDue(paid.plusSeconds(3599), 1000); assertEquals(2, flows(id));
        settlements.unfreezeDue(paid.plusSeconds(3600), 1000);
        posted(id, buyer, "USER_FROZEN", "USER_AVAILABLE", "4.50");
        posted(id, inviter, "USER_FROZEN", "USER_AVAILABLE", "2.25");
        money("4.50", balance(buyer, "balance")); money("2.25", balance(inviter, "balance"));
        audit(buyer); audit(inviter);
        var partial = event(id, "partial", OrderStatus.RECEIVED, OrderStatus.PARTIAL_REFUND, paid, "10", "3.33", paid.plusSeconds(4000));
        settlements.onOrderEvent(partial);
        // Rounded TOTAL recovery 6.75 * .333 = 2.25; deterministic split 1.50 + .75.
        money("1.50", item(id, "REBATE", "recovered_amount")); money("0.75", item(id, "REFERRAL", "recovered_amount"));
        long before = flows(id); settlements.onOrderEvent(partial); assertEquals(before, flows(id));
        audit(buyer); audit(inviter);
        settlements.onOrderEvent(event(id, "more", OrderStatus.PARTIAL_REFUND, OrderStatus.PARTIAL_REFUND,
            paid, "10", "7", paid.plusSeconds(4001)));
        // CUMULATIVE, not an additional 7: rounded pool 4.73 = 3.15 + 1.58.
        money("3.15", item(id, "REBATE", "recovered_amount")); money("1.58", item(id, "REFERRAL", "recovered_amount"));
        posted(id, buyer, "USER_AVAILABLE", "REBATE_COST", "3.15");
        posted(id, inviter, "USER_AVAILABLE", "REWARD_COST", "1.58");
        money("1.35", balance(buyer, "balance")); money("0.67", balance(inviter, "balance"));
        money("0", balance(buyer, "frozen_amount")); money("0", balance(inviter, "frozen_amount"));
        assertEquals(8, flows(id)); before = flows(id);
        settlements.unfreezeDue(paid.plusSeconds(7200), 1000); assertEquals(before, flows(id));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=?", id));
        evidenceReadOnce(); noCurrentInviterMoney(); audit(buyer); audit(inviter);
    }

    @Test public void unknownHistoryHasNoForecastReferralAndCreditsOnlyBuyerWithManualReview() {
        unknownHistory = true; publish(); configureFreeze(3600);
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "paid", null, OrderStatus.PAID, paid, null, null, paid));
        assertEquals(0, flows(id));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=?", id));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND type='REFERRAL'", id));
        money("4.50", item(id, "REBATE", "est_amount"));
        assertNull(frozen(id).inviterUserId()); assertNull(frozen(id).rule().evidence().validOrders());
        assertEquals(1, terms(id).benefits().size());
        assertEquals("MANUAL_REVIEW", jdbc.queryForObject(
            "SELECT review_status FROM cps_payment_invitation_snapshot WHERE order_line_id=?", String.class, id));
        assertEquals(1, count("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=? AND reason='INVITATION_HISTORY_UNKNOWN_BUYER_ONLY'", id));
        assertTrue(rewardSnapshot(id).historicalUnknown()); assertNull(rewardSnapshot(id).inviterId());
        assertTrue(rewardSnapshot(id).configs().isEmpty());
        assertFalse(frozen(id).rule().decision().manualReviewReasons().isEmpty());
        forbidEvidenceRead = true;
        Instant received = paid.plusSeconds(1);
        settlements.onOrderEvent(event(id, "received", OrderStatus.PAID, OrderStatus.RECEIVED, paid, "10", null, received));
        money("4.50", balance(buyer, "frozen_amount"));
        posted(id, buyer, "REBATE_COST", "USER_FROZEN", "4.50"); audit(buyer);
        settlements.unfreezeDue(received.plusSeconds(3600), 1000);
        money("4.50", balance(buyer, "balance")); money("0", balance(buyer, "frozen_amount"));
        posted(id, buyer, "USER_FROZEN", "USER_AVAILABLE", "4.50");
        assertEquals(2, flows(id));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", inviter));
        assertEquals(0, count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=?", id));
        assertEquals(1, terms(id).benefits().size()); evidenceReadOnce(); noCurrentInviterMoney(); audit(buyer);
    }

    @Test public void actualCommissionRepricingPostsOldImmutableRuleAndFinanceAfterNewPublication() {
        var old = publish(); configureFreeze(3600);
        var oldFinance = configuration.history().getFirst();
        Instant paid = databaseNow(); long id = order();
        settlements.onOrderEvent(event(id, "paid", null, OrderStatus.PAID, paid, null, null, paid));
        assertEquals(0, flows(id));
        String originalTerms = termsJson(id), originalRule = versions.snapshot(id);
        var originalFrozen = frozen(id);
        // DB assigns immutable publication timestamps; wait there rather than backdate any rows.
        jdbc.queryForObject("SELECT SLEEP(0.01)", Integer.class);
        var draft = drafts.byPlatform("TBK");
        draft = rules.update(draft.id(), draft.revision(), policy("0.10", false), buyer);
        var latest = rules.publish(draft.id(), draft.revision(), buyer); configureFreeze(7200);
        assertTrue(latest.effectiveAt().isAfter(paid)); assertNotEquals(old.id(), latest.id());
        assertNotEquals(oldFinance.id(), configuration.history().getFirst().id());
        assertEquals(old.config(), rules.history(old.documentId()).get(1).config());
        forbidEvidenceRead = true;
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=NULL WHERE user_id=?", buyer);
        Instant received = paid.plusSeconds(2);
        settlements.onOrderEvent(event(id, "received", OrderStatus.PAID, OrderStatus.RECEIVED, paid, "20", null, received));
        assertEquals(originalTerms, termsJson(id)); assertEquals(originalRule, versions.snapshot(id));
        assertEquals(originalFrozen, frozen(id)); assertEquals(oldFinance.id(), originalFrozen.configuration().id());
        var actualTerms = JSON.parseObject(jdbc.queryForObject(
            "SELECT actual_terms_json FROM cps_settlement_position WHERE order_line_id=?", String.class, id), SettlementTermsPort.Terms.class);
        assertEquals(originalFrozen, JSON.parseObject(actualTerms.ruleSnapshotJson(), RuleSettlementTermsBridge.Frozen.class));
        assertEquals(terms(id).freezePolicy(), actualTerms.freezePolicy());
        assertEquals(0, count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND rule_version_id<>?", id, old.id()));
        money("4.50", item(id, "REBATE", "est_amount")); money("9", item(id, "REBATE", "final_amount"));
        money("2.25", item(id, "REFERRAL", "est_amount")); money("4.50", item(id, "REFERRAL", "final_amount"));
        posted(id, buyer, "REBATE_COST", "USER_FROZEN", "9");
        posted(id, inviter, "REWARD_COST", "USER_FROZEN", "4.50");
        money("9", balance(buyer, "frozen_amount")); money("4.50", balance(inviter, "frozen_amount"));
        assertEquals(received.plusSeconds(3600), jdbc.queryForObject(
            "SELECT unfreeze_at FROM cps_settle_item WHERE order_line_id=? AND type='REBATE'", Timestamp.class, id).toInstant());
        audit(buyer); audit(inviter);
        settlements.unfreezeDue(received.plusSeconds(3599), 1000); assertEquals(2, flows(id));
        settlements.unfreezeDue(received.plusSeconds(3600), 1000);
        posted(id, buyer, "USER_FROZEN", "USER_AVAILABLE", "9");
        posted(id, inviter, "USER_FROZEN", "USER_AVAILABLE", "4.50");
        money("9", balance(buyer, "balance")); money("4.50", balance(inviter, "balance"));
        assertEquals(4, flows(id)); evidenceReadOnce(); noCurrentInviterMoney(); audit(buyer); audit(inviter);
    }

    private RulePolicy policy(String ordinary, boolean referral) {
        var target = new RulePolicy.Target(Set.of(), Set.of(), Set.of(), Set.of());
        var base = new RulePolicy.Proportion("ordinary", RulePolicy.Kind.BASE, RulePolicy.Mode.FIXED,
            bd(ordinary), target, 0, 0, null, List.of());
        var incentive = new RulePolicy.Incentive("referral", RulePolicy.IncentiveKind.INVITATION_COMMISSION,
            bd("0"), bd("0.50"), 0, 0, null, null);
        return new RulePolicy(Platform.TBK, bd("0.10"), bd("0"), bd("20"), bd("-0.01"), bd("0"),
            bd("0"), false, List.of(base), referral ? List.of(incentive) : List.of());
    }
    private RuleDocuments.Version publish() {
        var draft = rules.create(policy("0.50", true), buyer);
        return rules.publish(draft.id(), draft.revision(), buyer);
    }
    private void configureFreeze(long seconds) {
        var freeze = new ConfiguredSettlementFreezePort.Configuration(
            Map.of(Platform.TBK, List.of(new ConfiguredSettlementFreezePort.Band(bd("0"), seconds))),
            Map.of(Platform.TBK, seconds), false);
        configuration.publish(new SettlementConfigurationService.Settings(freeze, bd("0"), bd("0"), bd("20")), buyer);
    }
    private void user(long id) {
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,?,'0','0')",
            id, "production-settle-" + id, "SYNTHETIC settlement test");
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)", id, "production-settle-openid-" + id);
    }
    private long order() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,sub_order_no,platform_type,agent_user_id,buyer_user_id,order_amount,order_status) "
            + "VALUES (?,?,'line','tbk',?,?,100,'pending')", id, "production-settle-order-" + id, buyer, buyer);
        // Legacy NOT NULL agent_user_id is ownership, not proof of agent approval/qualification.
        return id;
    }
    private OrderEvent event(long id, String key, OrderStatus from, OrderStatus to, Instant paid,
            String actual, String recovered, Instant at) {
        var line = new UnionOrderLine(Platform.TBK, "production-settle-order-" + id, "line", "item", "pid", null,
            bd("100"), bd("10"), actual == null ? null : bd(actual), "SYNTHETIC_TEST_PAYLOAD", paid,
            to == OrderStatus.PAID ? null : at, null);
        return new OrderEvent(id + ":" + key, id, line, from, to, null, recovered == null ? null : bd(recovered),
            "PRODUCTION_SETTLEMENT_MYSQL_TEST", id, at);
    }
    private JdbcTemplate fixtureJdbc(DriverManagerDataSource migrated) {
        // serverTimezone alone tells Connector/J how to interpret timestamps; it does NOT
        // set MySQL's session time_zone. A UTC URL with a +08:00 server session
        // makes CURRENT_TIMESTAMP appear eight hours in the future to RuleEngine.
        // Use a numeric JVM-local offset for BOTH ends (also matching DATETIME conversion),
        // on EVERY connection; this needs no MySQL named-zone tables or global/server change.
        String url = migrated.getUrl();
        int question = url.indexOf('?');
        String base = question < 0 ? url : url.substring(0, question);
        String options = question < 0 ? "" : Arrays.stream(url.substring(question + 1).split("&"))
            .filter(option -> !option.matches("(?i)(serverTimezone|connectionTimeZone|forceConnectionTimeZoneToSession)=.*"))
            .filter(option -> !option.isEmpty()).collect(Collectors.joining("&"));
        String offset = Instant.now().atZone(ZoneId.systemDefault()).getOffset().getId();
        if (offset.equals("Z")) offset = "+00:00";
        String fixtureUrl = base + "?" + (options.isEmpty() ? "" : options + "&")
            + "connectionTimeZone=" + URLEncoder.encode(offset, StandardCharsets.UTF_8)
            + "&forceConnectionTimeZoneToSession=true";
        // Same UUID schema and credentials, but only this test's services use these options.
        return new JdbcTemplate(new DriverManagerDataSource(fixtureUrl, migrated.getUsername(), migrated.getPassword()));
    }
    private Instant databaseNow() {
        Instant paid = LedgerSqlTestClock.now(jdbc);
        assertFalse("fixture database paidAt must not be future: " + paid, paid.isAfter(Instant.now()));
        assertNotNull("fixture payment must follow real rule publication", versions.asOf("TBK", paid));
        assertNotNull("fixture payment must follow real finance publication", configuration.atPayment(paid));
        return paid;
    }
    private String termsJson(long id) {
        return jdbc.queryForObject("SELECT terms_json FROM cps_settlement_position WHERE order_line_id=?", String.class, id);
    }
    private SettlementTermsPort.Terms terms(long id) { return JSON.parseObject(termsJson(id), SettlementTermsPort.Terms.class); }
    private RuleSettlementTermsBridge.Frozen frozen(long id) {
        return JSON.parseObject(terms(id).ruleSnapshotJson(), RuleSettlementTermsBridge.Frozen.class);
    }
    private RewardApi.Snapshot rewardSnapshot(long id) {
        return JSON.parseObject(jdbc.queryForObject("SELECT payload FROM cps_reward_order_snapshot WHERE order_id=?", String.class, id), RewardApi.Snapshot.class);
    }
    private BigDecimal item(long id, String type, String field) {
        assertTrue(Set.of("est_amount", "final_amount", "recovered_amount").contains(field));
        return jdbc.queryForObject("SELECT " + field + " FROM cps_settle_item WHERE order_line_id=? AND type=?", BigDecimal.class, id, type);
    }
    private BigDecimal balance(long user, String field) {
        assertTrue(Set.of("balance", "frozen_amount").contains(field));
        var rows = jdbc.queryForList("SELECT " + field + " FROM sys_agent_commission_account WHERE user_id=?", BigDecimal.class, user);
        return rows.isEmpty() ? bd("0") : rows.getFirst();
    }
    private void posted(long id, long user, String debit, String credit, String expected) {
        // Production contract postings populate related_order_id, NOT legacy order_commission_id.
        assertTrue(count("SELECT COUNT(*) FROM sys_commission_flow WHERE related_order_id=? AND user_id=? AND debit_account=? AND credit_account=?",
            Long.toString(id), user, debit, credit) > 0);
        money(expected, jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM sys_commission_flow "
            + "WHERE related_order_id=? AND user_id=? AND debit_account=? AND credit_account=?", BigDecimal.class, Long.toString(id), user, debit, credit));
    }
    private void audit(long user) {
        var result = new LedgerAuditService(jdbc).checkAfter(user - 1, 1).getFirst();
        assertEquals(user, result.userId()); assertTrue("account cache must reconstruct from posted ledger", result.matches());
        money("0", result.withdrawing()); money("0", result.paid());
    }
    private void evidenceReadOnce() { assertEquals(1, invitationReads); assertEquals(1, qualificationReads); }
    private void noCurrentInviterMoney() {
        assertEquals(0, count("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?", currentInviter));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_agent_commission_account WHERE user_id=?", currentInviter));
    }
    private long flows(long id) { return count("SELECT COUNT(*) FROM sys_commission_flow WHERE related_order_id=?", Long.toString(id)); }
    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private static BigDecimal bd(String value) { return new BigDecimal(value).setScale(2); }
    private static void money(String expected, BigDecimal actual) { assertNotNull(actual); assertEquals(expected, 0, bd(expected).compareTo(actual)); }
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
}
