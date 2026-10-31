package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.contract.*;
import com.ruoyi.system.cps.mapper.*;
import com.ruoyi.system.cps.recon.*;
import com.ruoyi.system.cps.risk.*;
import com.ruoyi.system.cps.rule.*;
import com.ruoyi.system.cps.settlement.*;
import com.ruoyi.system.cps.withdraw.*;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.*;

/**
 * Composed production rules/settlement/reward/ledger/withdrawal/risk/exposure SQL acceptance.
 * Existing isolated MySQL harness is reused without changing old tests. Only line 9
 * payment history and configuration lookup are explicit evidence fixtures; withdrawal
 * uses the repository's durable NON-PRODUCTION MockWithdrawChannel. These tests do
 * not claim a real payment provider, automatic line 10 import or notification delivery.
 */
public class ProductionAcceptanceMatrixMySqlTest {
    private ProductionSettlementMySqlTest fixture;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private DefaultSettlementService settlements;
    private RuleEngine rules;
    private SettlementConfigurationService finance;
    private WithdrawalService withdrawals;
    private ExposureService exposure;
    private RiskService risk;
    private SettlementPenaltyImportService penalties;
    private long buyer, inviter;
    private long historicalOrders;

    @Before public void wireProductionComposition() throws Exception {
        fixture=new ProductionSettlementMySqlTest();
        fixture.wireProductionServices();
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(fixture,"jdbc");
        manager=(DataSourceTransactionManager)ReflectionTestUtils.getField(fixture,"manager");
        settlements=(DefaultSettlementService)ReflectionTestUtils.getField(fixture,"settlements");
        rules=(RuleEngine)ReflectionTestUtils.getField(fixture,"rules");
        finance=(SettlementConfigurationService)ReflectionTestUtils.getField(fixture,"configuration");
        buyer=(Long)ReflectionTestUtils.getField(fixture,"buyer");
        inviter=(Long)ReflectionTestUtils.getField(fixture,"inviter");
        RulePorts.Qualification explicitHistoricalFixture=(id,user,line,at) ->
            new RuleDecision.Evidence(at,"TEST-ONLY-LINE9-PENDING-MATRIX:"+id,historicalOrders,bd("0"),
                0L,inviter,false,at.minusSeconds(86400),null,null,null,null,null);
        ReflectionTestUtils.setField(rules,"qualifications",provider(RulePorts.Qualification.class,explicitHistoricalFixture));
        var factory=new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/cps/WithdrawalMapper.xml"),
            new ClassPathResource("mapper/cps/ReconBatchMapper.xml"));
        var sessions=new SqlSessionTemplate(factory.getObject());
        exposure=proxy(new ExposureService(sessions.getMapper(ReconBatchMapper.class)),ExposureService.class);
        ISysConfigService configs=mock(ISysConfigService.class);
        when(configs.selectConfigByKey(anyString())).thenAnswer(call -> {
            List<String> values=jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=?",String.class,call.<String>getArgument(0));
            return values.isEmpty() ? "" : values.getFirst();
        });
        risk=proxy(new RiskService(jdbc,configs),RiskService.class);
        var health=new HealthMonitorService(jdbc,configs,manager);
        var withdrawalMapper=sessions.getMapper(WithdrawalMapper.class);
        LedgerService ledger=(LedgerService)ReflectionTestUtils.getField(settlements,"ledger");
        withdrawals=new WithdrawalService(withdrawalMapper,provider(LedgerService.class,ledger),
            provider(WithdrawChannel.class,new MockWithdrawChannel(withdrawalMapper)),risk,exposure,health,manager);
        penalties=new SettlementPenaltyImportService(jdbc,settlements,manager);
        jdbc.update("UPDATE cps_withdraw_policy SET config=? WHERE id=1","""
            {"enabled":true,"channel":"mock","businessTimezone":"Asia/Shanghai",
             "autoPayEnabled":false,"autoPayBelowAmount":"0",
             "maximum":"0","dailyCount":0,"recipientDailyCount":0,"monthlyAmount":"0","monthDays":"0",
             "integerOnly":false,"requireReceivedOrder":false,"realNameSingle":"0","realNameMonthly":"0",
             "reviewAmount":"0","reviewFirstCount":0,"firstCountBasis":"SUCCEEDED","identityAction":"MANUAL_REVIEW",
             "channelSingle":"0","channelDaily":"0","channelUserDaily":"0","groups":{}}
            """);
        configure("commission.min.withdraw","0.01");
        configure("cps.withdraw.mock.status","SUCCESS");
        exposure.configure(new ExposurePolicy(bd("0"),ExposureLimitAction.MANUAL_REVIEW,60),buyer);
        jdbc.update("INSERT INTO cps_withdraw_recipient(user_id,channel,recipient_name,recipient_account,recipient_hash,"
            + "verified,verification_ref,updated_by,updated_at) VALUES(?,'mock','SYNTHETIC payee',?,?,1,'SYNTHETIC evidence',?,CURRENT_TIMESTAMP(3))",
            buyer,"matrix-recipient-"+buyer,WithdrawalService.recipientHash("mock","matrix-recipient-"+buyer),buyer);
    }
    @After public void cleanup() throws Exception { fixture.dropOnlyIsolatedSchema(); }

    @Test public void case05PaidWithdrawalThenInvalidIsHeldBySharedContractWithoutInventedRefund() {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long order=order(Platform.TBK);
        receiveAndRelease(order,paid);
        pay("4.50","matrix-case05");
        money("0",balance(buyer)); money("4.50",account(buyer,"withdrawn"));
        long flows=count("SELECT COUNT(*) FROM sys_commission_flow");
        var invalid=event(order,"invalid-after-payout",OrderStatus.RECEIVED,OrderStatus.INVALID,paid,"10",null,paid.plusSeconds(4000));
        settlements.onOrderEvent(invalid); settlements.onOrderEvent(invalid);
        assertEquals("RECEIVED",jdbc.queryForObject("SELECT order_status FROM cps_settlement_position WHERE order_line_id=?",String.class,order));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=? AND reason='ILLEGAL_INVALID_TRANSITION_REQUIRES_MANUAL_REVIEW'",order));
        assertEquals(flows,count("SELECT COUNT(*) FROM sys_commission_flow"));
        money("0",balance(buyer)); money("4.50",account(buyer,"withdrawn"));
        audit(buyer);
        // Explicit BLOCKER assertion, not acceptance of the missing INVALID recovery path:
        // shared ORDER forbids RECEIVED/SETTLED -> INVALID; line 8 must decide that edge.
        assertFalse(StatusTransitions.ORDER.get(OrderStatus.RECEIVED).contains(OrderStatus.INVALID));
    }

    @Test public void case07ManualPartialPenaltyRecoversRebateReferralAndSubsidyCumulatively() {
        var subsidy=new RulePolicy.Incentive("matrix-subsidy",RulePolicy.IncentiveKind.FIRST_N_SUBSIDY,
            bd("0"),bd("0.50"),2,0,null,null);
        publish(Platform.TBK,true,List.of(subsidy));
        Instant paid=now(); long order=order(Platform.TBK);
        receiveAndRelease(order,paid);
        money("6.75",balance(buyer)); money("2.25",balance(inviter));
        var request=request("matrix-case07-a",List.of(penalty(order,paid.plusSeconds(4000),"2.50")));
        var first=penalties.importStatement(request,buyer);
        assertEquals("APPLIED",first.lines().getFirst().status());
        money("5.06",balance(buyer)); money("1.69",balance(inviter));
        money("1.13",item(order,"REBATE","recovered_amount"));
        money("0.56",item(order,"REFERRAL","recovered_amount"));
        money("0.56",jdbc.queryForObject("SELECT recovered FROM cps_reward_award WHERE order_id=?",BigDecimal.class,order));
        long count=count("SELECT COUNT(*) FROM sys_commission_flow");
        assertEquals(first.importId(),penalties.importStatement(request,buyer).importId());
        penalties.retry(first.importId());
        assertEquals(count,count("SELECT COUNT(*) FROM sys_commission_flow"));
        var second=penalties.importStatement(request("matrix-case07-b",List.of(penalty(order,paid.plusSeconds(4001),"5.00"))),buyer);
        assertEquals("APPLIED",second.lines().getFirst().status());
        money("3.37",balance(buyer)); money("1.12",balance(inviter));
        money("2.25",item(order,"REBATE","recovered_amount")); money("1.13",item(order,"REFERRAL","recovered_amount"));
        assertEquals(2,count("SELECT COUNT(*) FROM cps_punish_import WHERE operator=?",buyer));
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_penalty_import WHERE operator_id=? AND payload LIKE '%SYNTHETIC authority evidence%'",buyer));
        assertEquals(6,count("SELECT COUNT(*) FROM cps_settlement_notification WHERE reason='WHOLE_MONTH_PENALTY'"));
        audit(buyer); audit(inviter);
    }

    @Test public void case10QuantityAwardDropsThroughProductionRefundAndCannotPayAgainOnReplay() {
        configure(RuleSafetyConfiguration.PREFIX+"TBK.minimumMargin","-20.00");
        var quantity=new RulePolicy.Incentive("matrix-two-orders",RulePolicy.IncentiveKind.ORDER_COUNT,bd("2"),bd("0"),2,0,null,null);
        publish(Platform.TBK,false,List.of(quantity));
        Instant firstPaid=now(); long first=order(Platform.TBK);
        receiveAndRelease(first,firstPaid);
        money("4.50",balance(buyer));
        historicalOrders=1;
        Instant secondPaid=now(); long second=order(Platform.TBK);
        receiveAndRelease(second,secondPaid);
        money("11",balance(buyer));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=? AND type='OWN_QUANTITY'",second));
        var refund=event(first,"quantity-contribution-refund",OrderStatus.RECEIVED,OrderStatus.FULL_REFUND,
            firstPaid,"10","10",firstPaid.plusSeconds(7200));
        settlements.onOrderEvent(refund);
        money("4.50",balance(buyer));
        money("2",jdbc.queryForObject("SELECT recovered FROM cps_reward_award WHERE order_id=? AND type='OWN_QUANTITY'",BigDecimal.class,second));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_reward_instruction WHERE cause_order_id=? AND JSON_EXTRACT(payload,'$.recovery')=true",first));
        long flows=count("SELECT COUNT(*) FROM sys_commission_flow");
        settlements.onOrderEvent(refund); settlements.unfreezeDue(firstPaid.plusSeconds(10800),1000);
        assertEquals(flows,count("SELECT COUNT(*) FROM sys_commission_flow"));
        money("4.50",balance(buyer)); audit(buyer);
    }

    @Test public void case14BanBlocksWithdrawalButPreservesAccrualAndAllowsAuthoritativeRecovery() {
        publish(Platform.TBK,false,List.of());
        risk.review(buyer,true,false,true,"SYNTHETIC case14 withdrawal-only ban",buyer);
        Instant paid=now(); long order=order(Platform.TBK);
        receiveAndRelease(order,paid);
        money("4.50",balance(buyer));
        var rejected=assertThrows(ServiceException.class,()->withdrawals.apply(buyer,new WithdrawalApplyRequest(bd("1"),"matrix-case14-ban")));
        assertTrue(rejected.getMessage().contains("限制提现"));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_withdrawal WHERE user_id=?",buyer));
        settlements.onOrderEvent(event(order,"banned-refund",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,paid,"10","5",paid.plusSeconds(4000)));
        money("2.25",balance(buyer));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_risk_user_state WHERE user_id=? AND withdrawal_blocked=1",buyer));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_risk_review WHERE user_id=?",buyer));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_notification WHERE user_id=? AND loss_amount=2.25",buyer));
        audit(buyer);
    }

    @Test public void case15PddFinalZeroCommissionIsNotAnEffectiveRewardOrderWithoutGuessingReason() {
        publish(Platform.PDD,false,List.of());
        Instant paid=now(); long order=order(Platform.PDD);
        settlements.onOrderEvent(event(order,"pdd-final-zero",null,OrderStatus.SETTLED,paid,"0",null,paid));
        settlements.unfreezeDue(paid.plusSeconds(3600),1000);
        money("0",balance(buyer));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_reward_award WHERE order_id=?",order));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_reward_order_state WHERE order_id=? AND valid=0 AND closed=1",order));
        assertEquals(0,count("SELECT COUNT(*) FROM sys_commission_flow WHERE related_order_id=?",Long.toString(order)));
        // Zero alone does NOT prove price comparison; no fabricated reason or delivered notice.
    }

    @Test public void case17WholeMonthExplicitPenaltyLinesKeepPaidOutDebtInRealExposure() {
        publish(Platform.TBK,false,List.of());
        Instant firstPaid=now(); long first=order(Platform.TBK); receiveAndRelease(first,firstPaid);
        Instant secondPaid=now(); long second=order(Platform.TBK); receiveAndRelease(second,secondPaid);
        money("9",balance(buyer)); pay("9","matrix-case17-payout");
        money("9",exposure.capture().current()); money("9",account(buyer,"withdrawn"));
        var imported=penalties.importStatement(request("matrix-case17-month",List.of(
            penalty(first,firstPaid.plusSeconds(7200),"10"),penalty(second,secondPaid.plusSeconds(7200),"10"))),buyer);
        assertEquals(List.of("APPLIED","APPLIED"),imported.lines().stream().map(SettlementPenaltyImportService.LineResult::status).toList());
        money("-9",balance(buyer));
        money("9",exposure.current().current()); money("9",exposure.current().debtAmount());
        money("0",exposure.current().knownAmount());
        exposure.configure(new ExposurePolicy(bd("5"),ExposureLimitAction.MANUAL_REVIEW,60),buyer);
        var decision=exposure.evaluateWithdrawal(bd("1"));
        assertTrue(decision.manualReview()); assertFalse(decision.blocked());
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_notification WHERE reason='WHOLE_MONTH_PENALTY' AND user_id=?",buyer));
        money("9",jdbc.queryForObject("SELECT SUM(loss_amount) FROM cps_settlement_notification WHERE user_id=?",BigDecimal.class,buyer));
        long flows=count("SELECT COUNT(*) FROM sys_commission_flow");
        penalties.retry(imported.importId()); settlements.unfreezeDue(firstPaid.plusSeconds(14400),1000);
        assertEquals(flows,count("SELECT COUNT(*) FROM sys_commission_flow"));
        money("-9",balance(buyer)); audit(buyer);
    }

    @Test public void case18ActualCommissionLossCreatesPendingNotificationWithActualNewBalance() {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long order=order(Platform.TBK);
        receiveAndRelease(order,paid);
        var loss=event(order,"actual-zero",OrderStatus.RECEIVED,OrderStatus.RECEIVED,paid,"0",null,paid.plusSeconds(4000));
        settlements.onOrderEvent(loss); settlements.onOrderEvent(loss);
        money("0",balance(buyer));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_notification WHERE user_id=? AND reason='REBATE_INVALID' "
            + "AND loss_amount=4.50 AND balance_after=0 AND status='PENDING'",buyer));
        assertEquals(0,count("SELECT COUNT(*) FROM cps_settlement_notification WHERE status='SENT'"));
        audit(buyer);
        // Binding-failure trigger still belongs to line 9; PENDING is not provider delivery.
    }

    @Test public void manualPenaltyMissingAmountStopsHourlyReleaseAndMixedUnknownLineStaysExplainable() {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long order=order(Platform.TBK);
        settlements.onOrderEvent(event(order,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var result=penalties.importStatement(request("matrix-missing-amount",List.of(
            penalty(order,paid.plusSeconds(1),null),penalty(999_999_999L,paid.plusSeconds(1),"1"))),buyer);
        assertEquals("MANUAL_REVIEW",result.lines().get(0).status());
        assertEquals("ORDER_NOT_FOUND",result.lines().get(1).resultCode());
        money("4.50",account(buyer,"frozen_amount")); money("0",balance(buyer));
        settlements.unfreezeDue(paid.plusSeconds(86400),1000);
        money("4.50",account(buyer,"frozen_amount")); money("0",balance(buyer));
        long flows=count("SELECT COUNT(*) FROM sys_commission_flow");
        penalties.retry(result.importId());
        assertEquals(flows,count("SELECT COUNT(*) FROM sys_commission_flow"));
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=?",order));
        var conflict=request("matrix-missing-amount",List.of(penalty(order,paid.plusSeconds(1),"1")));
        assertThrows(IllegalArgumentException.class,()->penalties.importStatement(conflict,buyer));
        audit(buyer);
    }


    @Test public void concurrentManualPenaltyImportsCreateOneSourceAndOneRecovery() throws Exception {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long order=order(Platform.TBK); receiveAndRelease(order,paid);
        var request=request("matrix-concurrent-penalty",List.of(penalty(order,paid.plusSeconds(4000),"5")));
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var first=pool.submit(()->{ start.await(); return penalties.importStatement(request,buyer); });
            var second=pool.submit(()->{ start.await(); return penalties.importStatement(request,buyer); });
            start.countDown();
            assertEquals(first.get(30,java.util.concurrent.TimeUnit.SECONDS).importId(),
                second.get(30,java.util.concurrent.TimeUnit.SECONDS).importId());
            assertEquals(1,count("SELECT COUNT(*) FROM cps_punish_import WHERE operator=?",buyer));
            assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_penalty_line WHERE status='APPLIED'"));
            assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_notification WHERE user_id=?",buyer));
            money("2.25",balance(buyer)); audit(buyer);
        } finally {
            start.countDown(); pool.shutdownNow();
            assertTrue(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));
        }
    }


    @Test public void missingPenaltyTimeAndMissingPaymentSnapshotBothQuarantineKnownOrders() {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long funded=order(Platform.TBK), notYetProcessed=order(Platform.TBK);
        settlements.onOrderEvent(event(funded,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        var request=request("matrix-incomplete-source",List.of(
            penalty(funded,null,null),penalty(notYetProcessed,paid.plusSeconds(1),null)));
        var result=penalties.importStatement(request,buyer);
        assertEquals(List.of("MANUAL_REVIEW","MANUAL_REVIEW"),
            result.lines().stream().map(SettlementPenaltyImportService.LineResult::status).toList());
        assertEquals("MISSING_SOURCE_STATUS_TIME",result.lines().get(0).resultCode());
        assertEquals("MISSING_SETTLEMENT_COMMISSION_OR_PAYMENT_EVIDENCE",result.lines().get(1).resultCode());
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_position WHERE order_line_id IN (?,?) AND manual_hold=1",funded,notYetProcessed));
        long flows=count("SELECT COUNT(*) FROM sys_commission_flow");
        // Late genuine funding cannot erase the earlier unresolved penalty evidence.
        settlements.onOrderEvent(event(notYetProcessed,"late-received",null,OrderStatus.RECEIVED,paid,"10",null,paid.plusSeconds(2)));
        settlements.unfreezeDue(paid.plusSeconds(86400),1000);
        assertEquals(flows,count("SELECT COUNT(*) FROM sys_commission_flow"));
        money("4.50",account(buyer,"frozen_amount")); money("0",balance(buyer));
        assertEquals(result.importId(),penalties.importStatement(request,buyer).importId());
        penalties.retry(result.importId());
        assertEquals(2,count("SELECT COUNT(*) FROM cps_settlement_hold_event WHERE reason IN ('MISSING_SOURCE_STATUS_TIME','MISSING_SETTLEMENT_COMMISSION_OR_PAYMENT_EVIDENCE')"));
        audit(buyer);
    }


    @Test public void manualPenaltyKeepsEightDecimalAllianceEvidenceBeforeCentAllocation() {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long order=order(Platform.TBK); receiveAndRelease(order,paid);
        var result=penalties.importStatement(request("matrix-eight-decimal-penalty",
            List.of(penalty(order,paid.plusSeconds(4000),"0.12345678"))),buyer);
        assertEquals("APPLIED",result.lines().getFirst().status());
        money("0.12345678",jdbc.queryForObject("SELECT recovered_commission FROM cps_settlement_position WHERE order_line_id=?",BigDecimal.class,order));
        money("0.06",item(order,"REBATE","recovered_amount")); money("4.44",balance(buyer));
        audit(buyer);
    }


    @Test public void unsupportedPenaltyDuringDisputeCannotReleaseAfterLaterRestoration() {
        publish(Platform.TBK,false,List.of());
        Instant paid=now(); long order=order(Platform.TBK);
        settlements.onOrderEvent(event(order,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        settlements.onOrderEvent(event(order,"disputed",OrderStatus.RECEIVED,OrderStatus.DISPUTED,paid,"10",null,paid.plusSeconds(1)));
        var result=penalties.importStatement(request("matrix-disputed-penalty",
            List.of(penalty(order,paid.plusSeconds(2),"5"))),buyer);
        assertEquals("MANUAL_REVIEW",result.lines().getFirst().status());
        assertEquals(1,count("SELECT COUNT(*) FROM cps_settlement_position WHERE order_line_id=? AND manual_hold=1",order));
        var restoration=event(order,"restored",OrderStatus.DISPUTED,OrderStatus.RECEIVED,paid,"10",null,paid.plusSeconds(3));
        settlements.onOrderEvent(new OrderEvent(restoration.eventKey(),restoration.orderLineId(),restoration.line(),
            restoration.fromStatus(),restoration.toStatus(),OrderStatus.RECEIVED,null,restoration.source(),null,restoration.occurredAt()));
        settlements.unfreezeDue(paid.plusSeconds(86400),1000);
        money("0",balance(buyer)); money("4.50",account(buyer,"frozen_amount"));
        money("0",item(order,"REBATE","recovered_amount"));
        assertEquals(1,count("SELECT COUNT(*) FROM sys_commission_flow WHERE related_order_id=?",Long.toString(order)));
        penalties.retry(result.importId()); audit(buyer);
    }

    private void publish(Platform platform,boolean referral,List<RulePolicy.Incentive> extra) {
        var base=(RulePolicy)ReflectionTestUtils.invokeMethod(fixture,"policy","0.50",referral);
        var incentives=new java.util.ArrayList<>(base.incentives()); incentives.addAll(extra);
        BigDecimal floor=extra.stream().anyMatch(i->i.fixed().signum()>0) ? bd("-20") : bd("-0.01");
        configure(RuleSafetyConfiguration.PREFIX+platform.name()+".maximumActualCommission","20.00");
        configure(RuleSafetyConfiguration.PREFIX+platform.name()+".minimumMargin",floor.toPlainString());
        var policy=new RulePolicy(platform,base.serviceRate(),base.jdNonSelfRate(),base.maximumCommission(),floor,
            base.payoutCostRate(),base.minimumRebate(),base.targetedWithTier(),base.proportions(),incentives);
        var draft=rules.create(policy,buyer); rules.publish(draft.id(),draft.revision(),buyer);
        var freeze=new ConfiguredSettlementFreezePort.Configuration(
            Map.of(platform,List.of(new ConfiguredSettlementFreezePort.Band(bd("0"),3600L))),Map.of(platform,3600L),false);
        finance.publish(new SettlementConfigurationService.Settings(freeze,bd("0"),bd("0"),bd("20")),buyer);
    }
    private long order(Platform platform) {
        long id=ReflectionTestUtils.invokeMethod(fixture,"order");
        if(platform!=Platform.TBK) jdbc.update("UPDATE sys_order_commission SET platform_type=? WHERE id=?",platform.name().toLowerCase(java.util.Locale.ROOT),id);
        return id;
    }
    private Instant now() { return LedgerSqlTestClock.now(jdbc); }
    private OrderEvent event(long order,String key,OrderStatus from,OrderStatus to,Instant paid,String actual,String recovered,Instant at) {
        Platform platform=Platform.valueOf(jdbc.queryForObject("SELECT platform_type FROM sys_order_commission WHERE id=?",String.class,order).toUpperCase(java.util.Locale.ROOT));
        var line=new UnionOrderLine(platform,"production-settle-order-"+order,"line","item","pid",null,bd("100"),bd("10"),
            actual==null ? null : bd(actual),"SYNTHETIC authority evidence",paid,to==OrderStatus.PAID ? null : at,
            to==OrderStatus.SETTLED ? at : null,"{\"source\":\"synthetic acceptance fixture\"}",at);
        return new OrderEvent(order+":"+key,order,line,from,to,null,recovered==null ? null : bd(recovered),"SYNTHETIC_MATRIX",null,at);
    }
    private void receiveAndRelease(long order,Instant paid) {
        settlements.onOrderEvent(event(order,"received",null,OrderStatus.RECEIVED,paid,"10",null,paid));
        settlements.unfreezeDue(paid.plusSeconds(3600),1000);
    }
    private void pay(String amount,String key) {
        var applied=withdrawals.apply(buyer,new WithdrawalApplyRequest(bd(amount),key));
        var approved=withdrawals.audit(applied.withdrawNo(),true,buyer,"SYNTHETIC approval");
        assertEquals(WithdrawalStatus.APPROVED,approved.status());
        assertEquals(WithdrawalStatus.SUCCEEDED,withdrawals.pay(applied.withdrawNo(),buyer).status());
        assertEquals(1,count("SELECT COUNT(*) FROM cps_withdraw_mock_payment WHERE withdraw_no=?",applied.withdrawNo()));
    }
    private SettlementPenaltyImportService.Request request(String key,List<SettlementPenaltyImportService.Line> lines) {
        return new SettlementPenaltyImportService.Request(key,Platform.TBK,"SYNTHETIC-statement.json","SYNTHETIC authority evidence","2026-10",lines);
    }
    private SettlementPenaltyImportService.Line penalty(long order,Instant at,String recovered) {
        return new SettlementPenaltyImportService.Line(order,at,recovered==null ? null : bd(recovered),
            "{\"source\":\"SYNTHETIC authority evidence\",\"amountMeaning\":\"cumulative alliance commission\"}");
    }
    private void configure(String key,String value) {
        if(count("SELECT COUNT(*) FROM sys_config WHERE config_key=?",key)==0)
            jdbc.update("INSERT INTO sys_config(config_name,config_key,config_value,config_type) VALUES('SYNTHETIC matrix fixture',?,?,'N')",key,value);
        else jdbc.update("UPDATE sys_config SET config_value=? WHERE config_key=?",value,key);
    }
    private BigDecimal balance(long user) {
        List<BigDecimal> values=jdbc.queryForList("SELECT balance FROM sys_agent_commission_account WHERE user_id=?",BigDecimal.class,user);
        return values.isEmpty() ? bd("0") : values.getFirst();
    }
    private BigDecimal account(long user,String column) {
        assertTrue(Set.of("frozen_amount","withdrawn").contains(column));
        return jdbc.queryForObject("SELECT "+column+" FROM sys_agent_commission_account WHERE user_id=?",BigDecimal.class,user);
    }
    private BigDecimal item(long order,String type,String column) {
        assertEquals("recovered_amount",column);
        return jdbc.queryForObject("SELECT recovered_amount FROM cps_settle_item WHERE order_line_id=? AND type=?",BigDecimal.class,order,type);
    }
    private long count(String sql,Object...args) { return jdbc.queryForObject(sql,Long.class,args); }
    private void audit(long user) {
        var check=new LedgerAuditService(jdbc).checkAfter(user-1,1).getFirst();
        assertEquals(user,check.userId()); assertTrue("production ledger must reconstruct every account pocket",check.matches());
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void money(String expected,BigDecimal actual) { assertNotNull(actual); assertEquals(0,bd(expected).compareTo(actual)); }
    private static <T> ObjectProvider<T> provider(Class<T> type,T value) {
        var beans=new StaticListableBeanFactory(); beans.addBean("explicit-matrix-fixture",value); return beans.getBeanProvider(type);
    }
    private <T> T proxy(T target,Class<T> type) {
        var advice=new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory=new ProxyFactory(target); factory.setProxyTargetClass(true); factory.addAdvice(advice);
        return type.cast(factory.getProxy());
    }
}
