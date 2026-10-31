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
 * Missing-loss quarantine through production rule terms, actual MySQL ledger and hourly release.
 * Tests prove persisted holds, atomic idempotency, no debit and no release; INVALID recovery after
 * credit remains blocked by the unchanged shared ORDER contract and is NOT claimed complete.
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
public class SettlementRecoveryHoldMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(98_800_000L);
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
        var rewards = transactional(new RewardService(sessions.getMapper(RewardProgressMapper.class),
            new RewardRepricingJdbcStore(jdbc)), RewardService.class);
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


    @Test public void partialRefundWithoutAmountCommitsHoldAndHourlyNeverReleases() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        long originalFlows=flows(id);
        var missing=event(id,"missing-partial",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,
            paid,"20",null,paid.plusSeconds(60)); // A changed amount must not reprice before the hold.
        settlements.onOrderEvent(missing);
        held(id,"MISSING_CUMULATIVE_ALLIANCE_COMMISSION_RECOVERY",1);
        assertEquals(originalFlows,flows(id));
        money("10",positionMoney(id,"original_commission")); money("0",positionMoney(id,"recovered_commission"));
        money("4.50",balance(buyer,"frozen_amount")); money("2.25",balance(inviter,"frozen_amount"));
        money("0",balance(buyer,"balance")); money("0",balance(inviter,"balance"));
        assertEquals("RECEIVED",positionState(id));
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?",id));
        assertEquals("PARTIAL_REFUND",jdbc.queryForObject(
            "SELECT JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.toStatus')) FROM cps_settlement_hold_event WHERE event_key=?",
            String.class,missing.eventKey()));
        for (int hour=1;hour<=4;hour++) assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(hour*3600L),1000));
        assertEquals(originalFlows,flows(id)); audit(buyer); audit(inviter);
    }

    @Test public void penaltyWithoutAmountHoldsAlreadyMatureFrozenFunds() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        settlements.onOrderEvent(event(id,"missing-penalty",OrderStatus.RECEIVED,OrderStatus.PENALIZED,
            paid,null,null,paid.plusSeconds(7200)));
        held(id,"MISSING_CUMULATIVE_ALLIANCE_COMMISSION_RECOVERY",1);
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? "
            +"AND freeze_paused=1 AND freeze_remaining_millis=0",id));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(10800),1000));
        assertEquals(2,flows(id)); money("4.50",balance(buyer,"frozen_amount"));
        money("0",balance(buyer,"balance")); money("0",item(id,"REBATE","recovered_amount"));
        audit(buyer); audit(inviter);
    }

    @Test public void duplicateMissingRecoveryIsAtomicAcrossConcurrentWorkers() throws Exception {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var missing=event(id,"concurrent-missing",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,
            paid,"10",null,paid.plusSeconds(60));
        var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int n=0;n<6;n++) futures.add(pool.submit(() -> {
                try { start.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                settlements.onOrderEvent(missing);
            }));
            start.countDown();
            for (var future:futures) future.get(30,java.util.concurrent.TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        held(id,"MISSING_CUMULATIVE_ALLIANCE_COMMISSION_RECOVERY",1);
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?",id));
        assertEquals(2,flows(id));
        String before=jdbc.queryForObject("SELECT CAST(event_json AS CHAR) FROM cps_settlement_hold_event WHERE event_key=?",
            String.class,missing.eventKey());
        settlements.onOrderEvent(missing);
        assertEquals(before,jdbc.queryForObject("SELECT CAST(event_json AS CHAR) FROM cps_settlement_hold_event WHERE event_key=?",
            String.class,missing.eventKey()));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE order_line_id=?",id));
        var changedPayload=event(id,"concurrent-missing",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,
            paid,"10","5",paid.plusSeconds(60));
        assertThrows(IllegalArgumentException.class,() -> settlements.onOrderEvent(changedPayload));
        assertEquals(2,flows(id));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000)); audit(buyer); audit(inviter);
    }

    @Test public void distinctMissingEventsAndLaterKnownEvidenceCannotResetOrClearHold() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var first=event(id,"missing-partial",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,
            paid,"10",null,paid.plusSeconds(30));
        var second=event(id,"missing-penalty",OrderStatus.RECEIVED,OrderStatus.PENALIZED,
            paid,"10",null,paid.plusSeconds(40));
        settlements.onOrderEvent(first); settlements.onOrderEvent(second);
        settlements.onOrderEvent(first); settlements.onOrderEvent(second);
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE order_line_id=?",id));
        assertEquals(3,count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?",id));
        assertEquals(paid.plusSeconds(30),jdbc.queryForObject(
            "SELECT paused_at FROM cps_settle_item WHERE order_line_id=? AND type='REBATE'",Timestamp.class,id).toInstant());
        var evidence=event(id,"later-known-amount",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,
            paid,"10","5",paid.plusSeconds(50));
        settlements.onOrderEvent(evidence); settlements.onOrderEvent(evidence);
        var settled=event(id,"later-settlement",OrderStatus.RECEIVED,OrderStatus.SETTLED,
            paid,"20",null,paid.plusSeconds(60));
        settlements.onOrderEvent(settled);
        held(id,"MANUAL_SETTLEMENT_HOLD_ACTIVE",2);
        assertEquals(4,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE order_line_id=?",id));
        assertEquals(2,flows(id)); assertEquals("RECEIVED",positionState(id));
        money("0",positionMoney(id,"recovered_commission")); money("10",positionMoney(id,"original_commission"));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000)); audit(buyer); audit(inviter);
    }

    @Test public void missingPenaltyOnFirstObservationBlocksLaterFundingWithoutInventingTerms() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"first-missing",null,OrderStatus.PENALIZED,paid,null,null,paid));
        assertEquals(Boolean.TRUE,jdbc.queryForObject(
            "SELECT manual_hold FROM cps_settlement_position WHERE order_line_id=?",Boolean.class,id));
        assertNull(jdbc.queryForObject("SELECT terms_json FROM cps_settlement_position WHERE order_line_id=?",String.class,id));
        assertEquals(0,flows(id));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=?",id));
        settlements.onOrderEvent(event(id,"later-received",null,OrderStatus.RECEIVED,paid,"10",null,paid.plusSeconds(60)));
        assertEquals(0,flows(id));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_payment_invitation_snapshot WHERE order_line_id=?",id));
        assertEquals(0,invitationReads); assertEquals(0,qualificationReads);
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE order_line_id=?",id));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000));
    }

    @Test public void missingRecoveryCannotResumeExistingDisputeClock() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var basis=event(id,"dispute",OrderStatus.RECEIVED,OrderStatus.DISPUTED,paid,"10",null,paid.plusSeconds(20));
        settlements.onOrderEvent(new OrderEvent(basis.eventKey(),id,basis.line(),basis.fromStatus(),basis.toStatus(),
            OrderStatus.RECEIVED,null,basis.source(),basis.rawId(),basis.occurredAt()));
        settlements.onOrderEvent(event(id,"missing-from-dispute",OrderStatus.DISPUTED,OrderStatus.PARTIAL_REFUND,
            paid,"10",null,paid.plusSeconds(30)));
        var restore=event(id,"restore",OrderStatus.DISPUTED,OrderStatus.RECEIVED,paid,"10",null,paid.plusSeconds(100));
        settlements.onOrderEvent(new OrderEvent(restore.eventKey(),id,restore.line(),restore.fromStatus(),restore.toStatus(),
            OrderStatus.RECEIVED,null,restore.source(),restore.rawId(),restore.occurredAt()));
        assertEquals("DISPUTED",positionState(id));
        assertEquals(paid.plusSeconds(20),jdbc.queryForObject(
            "SELECT paused_at FROM cps_settle_item WHERE order_line_id=? AND type='REBATE'",Timestamp.class,id).toInstant());
        held(id,"MANUAL_SETTLEMENT_HOLD_ACTIVE",1);
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000));
        assertEquals(2,flows(id)); money("4.50",balance(buyer,"frozen_amount")); audit(buyer); audit(inviter);
    }

    @Test public void heldSubsidyCannotBeQualifiedByHourlyRewardRetryOrStaleCandidate() {
        var base=policy("0.50",false);
        var subsidy=new RulePolicy.Incentive("held-first-n",RulePolicy.IncentiveKind.FIRST_N_SUBSIDY,
            bd("0"),bd("0.50"),1,0,null,null);
        var policy=new RulePolicy(base.platform(),base.serviceRate(),base.jdNonSelfRate(),base.maximumCommission(),
            base.minimumMargin(),base.payoutCostRate(),base.minimumRebate(),base.targetedWithTier(),
            base.proportions(),List.of(subsidy));
        var draft=rules.create(policy,buyer); rules.publish(draft.id(),draft.revision(),buyer); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        assertEquals(1,rewardSnapshot(id).configs().size());
        settlements.onOrderEvent(event(id,"missing",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,
            paid,"20",null,paid.plusSeconds(60)));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000));
        // Candidate discovery may precede a concurrently committed hold. The actual release
        // rechecks the position under the order lock, even if discovery returned that old candidate.
        new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status ->
            ReflectionTestUtils.invokeMethod(settlements,"release",id,paid.plusSeconds(7200)));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=?",id));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_reward_instruction WHERE cause_order_id=?",id));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_reward_order_state WHERE order_id=? AND qualified=1",id));
        assertEquals(1,flows(id)); money("4.50",balance(buyer,"frozen_amount")); money("0",balance(buyer,"balance")); audit(buyer);
    }

    @Test public void unknownPenaltyAfterReleaseNeverDebitsOrRepricesAvailableBalance() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        settlements.unfreezeDue(paid.plusSeconds(3600),1000);
        long before=flows(id);
        settlements.onOrderEvent(event(id,"post-release-missing",OrderStatus.RECEIVED,OrderStatus.PENALIZED,
            paid,"20",null,paid.plusSeconds(3601)));
        assertEquals(Boolean.TRUE,jdbc.queryForObject(
            "SELECT manual_hold FROM cps_settlement_position WHERE order_line_id=?",Boolean.class,id));
        assertEquals(before,flows(id)); money("4.50",balance(buyer,"balance")); money("2.25",balance(inviter,"balance"));
        money("10",positionMoney(id,"original_commission")); money("0",positionMoney(id,"recovered_commission"));
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND status='CREDITED'",id));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000)); audit(buyer); audit(inviter);
    }

    @Test public void fullRefundStillRequiresExactlyTheOriginalAllianceCommission() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        for (String recovery:new String[]{null,"7","10.01"}) {
            var invalid=event(id,"invalid-full-"+recovery,OrderStatus.RECEIVED,OrderStatus.FULL_REFUND,
                paid,"10",recovery,paid.plusSeconds(60));
            assertThrows(IllegalArgumentException.class,() -> settlements.onOrderEvent(invalid));
        }
        assertEquals(2,flows(id)); money("0",positionMoney(id,"recovered_commission"));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?",id));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE order_line_id=?",id));
        var full=event(id,"valid-full",OrderStatus.RECEIVED,OrderStatus.FULL_REFUND,paid,"10","10",paid.plusSeconds(61));
        settlements.onOrderEvent(full); long after=flows(id); settlements.onOrderEvent(full);
        assertEquals(4,after); assertEquals(after,flows(id));
        money("10",positionMoney(id,"recovered_commission"));
        money("0",balance(buyer,"frozen_amount")); money("0",balance(inviter,"frozen_amount"));
        assertEquals("FULL_REFUND",positionState(id)); audit(buyer); audit(inviter);
    }

    @Test public void illegalInvalidIsHeldWithoutInventingSharedOrderTransition() {
        assertFalse(StatusTransitions.ORDER.get(OrderStatus.RECEIVED).contains(OrderStatus.INVALID));
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var invalid=event(id,"illegal-invalid",OrderStatus.RECEIVED,OrderStatus.INVALID,paid,"10",null,paid.plusSeconds(60));
        settlements.onOrderEvent(invalid); settlements.onOrderEvent(invalid);
        held(id,"ILLEGAL_INVALID_TRANSITION_REQUIRES_MANUAL_REVIEW",1);
        assertEquals("RECEIVED",positionState(id));
        assertEquals("RECEIVED",jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?",String.class,id));
        assertEquals(2,flows(id)); money("0",positionMoney(id,"recovered_commission"));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000));
        settlements.onOrderEvent(event(id,"later-settled",OrderStatus.RECEIVED,OrderStatus.SETTLED,paid,"10",null,paid.plusSeconds(80)));
        assertEquals("RECEIVED",positionState(id)); assertEquals(2,flows(id)); audit(buyer); audit(inviter);
    }

    @Test public void holdStorageFailureRollsBackPauseAndReceiptThenRetryCommits() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var missing=event(id,"retry-hold",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,paid,"10",null,paid.plusSeconds(60));
        // Fault injection is restricted to this suite's disposable UUID schema.
        jdbc.execute("CREATE TRIGGER settlement_hold_failure BEFORE INSERT ON cps_settlement_hold_event "
            +"FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected hold INSERT failure'");
        try { assertThrows(RuntimeException.class,() -> settlements.onOrderEvent(missing)); }
        finally { jdbc.execute("DROP TRIGGER settlement_hold_failure"); }
        assertEquals(Boolean.FALSE,jdbc.queryForObject(
            "SELECT manual_hold FROM cps_settlement_position WHERE order_line_id=?",Boolean.class,id));
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND status='FROZEN' AND paused_at IS NULL",id));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?",id));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=?",id));
        assertEquals(2,flows(id)); settlements.onOrderEvent(missing);
        held(id,"MISSING_CUMULATIVE_ALLIANCE_COMMISSION_RECOVERY",1);
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000)); audit(buyer); audit(inviter);
    }


    @Test public void sourceEvidenceWithoutAllianceTimeHoldsIdempotentlyWithoutInventingOrderEvent() {
        publish(); configureFreeze(3600);
        Instant paid=databaseNow(); long id=order();
        settlements.onOrderEvent(event(id,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        String key="missing-time:"+id, reason="MISSING_SOURCE_STATUS_TIME";
        String raw="{\"source\":\"synthetic statement\",\"allianceStatusTime\":null,\"amount\":null,\"orderLineId\":"+id+"}";
        var repository=new SettlementRepository(jdbc);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(
            repository,"holdUnprocessableEvidence",id,key,reason,raw));
        Timestamp firstPause=jdbc.queryForObject(
            "SELECT paused_at FROM cps_settle_item WHERE order_line_id=? AND type='REBATE'",Timestamp.class,id);
        transaction.executeWithoutResult(status -> ReflectionTestUtils.invokeMethod(
            repository,"holdUnprocessableEvidence",id,key,reason,raw));
        held(id,reason,1);
        assertEquals(firstPause,jdbc.queryForObject(
            "SELECT paused_at FROM cps_settle_item WHERE order_line_id=? AND type='REBATE'",Timestamp.class,id));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE order_line_id=?",id));
        assertEquals(raw,jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.sourceEvidence')) "
            +"FROM cps_settlement_hold_event WHERE order_line_id=?",String.class,id));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_receipt WHERE order_line_id=?",id));
        assertEquals("RECEIVED",positionState(id));
        assertThrows(IllegalArgumentException.class,() -> transaction.executeWithoutResult(status ->
            ReflectionTestUtils.invokeMethod(repository,"holdUnprocessableEvidence",id,key,reason,raw+" ")));
        assertThrows(IllegalStateException.class,() -> ReflectionTestUtils.invokeMethod(
            repository,"holdUnprocessableEvidence",id,key,reason,raw));
        assertEquals(0,settlements.unfreezeDue(paid.plusSeconds(7200),1000));
        assertEquals(2,flows(id)); money("4.50",balance(buyer,"frozen_amount")); money("0",balance(buyer,"balance"));
        audit(buyer); audit(inviter);
    }

    private void held(long id,String reason,long reasonCount) {
        assertEquals(Boolean.TRUE,jdbc.queryForObject(
            "SELECT manual_hold FROM cps_settlement_position WHERE order_line_id=?",Boolean.class,id));
        assertEquals(reasonCount,count("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=? AND reason=?",id,reason));
        assertTrue(count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=?",id)>0);
        assertEquals(0,count("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? "
            +"AND (status<>'PAUSED' OR paused_at IS NULL OR freeze_paused<>1)",id));
    }
    private BigDecimal positionMoney(long id,String field) {
        assertTrue(Set.of("original_commission","recovered_commission").contains(field));
        return jdbc.queryForObject("SELECT "+field+" FROM cps_settlement_position WHERE order_line_id=?",BigDecimal.class,id);
    }
    private String positionState(long id) {
        return jdbc.queryForObject("SELECT order_status FROM cps_settlement_position WHERE order_line_id=?",String.class,id);
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
            id, "recovery-hold-" + id, "SYNTHETIC settlement test");
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)", id, "recovery-hold-openid-" + id);
    }
    private long order() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,sub_order_no,platform_type,agent_user_id,buyer_user_id,order_amount,order_status) "
            + "VALUES (?,?,'line','tbk',?,?,100,'pending')", id, "recovery-hold-order-" + id, buyer, buyer);
        // Legacy NOT NULL agent_user_id is ownership, not proof of agent approval/qualification.
        return id;
    }
    private OrderEvent event(long id, String key, OrderStatus from, OrderStatus to, Instant paid,
            String actual, String recovered, Instant at) {
        var line = new UnionOrderLine(Platform.TBK, "recovery-hold-order-" + id, "line", "item", "pid", null,
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
