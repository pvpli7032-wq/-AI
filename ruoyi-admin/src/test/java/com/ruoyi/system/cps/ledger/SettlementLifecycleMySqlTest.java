package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
import org.springframework.transaction.support.TransactionTemplate;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.contract.*;
import com.ruoyi.system.cps.mapper.RewardProgressMapper;
import com.ruoyi.system.cps.rule.*;
import com.ruoyi.system.cps.settlement.*;
import com.ruoyi.system.cps.settlement.SettlementTermsPort.*;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import com.ruoyi.system.mapper.*;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;
import com.ruoyi.union.adapter.*;

/**
 * Real MySQL component acceptance: actual settlement, ledger/account MyBatis, reward service,
 * invitation snapshot and transactional notification outbox. Uses the unchanged withdrawal
 * harness to migrate/drop ONLY its fresh UUID database; requires LINE4_MYSQL_URL (lead: 13317).
 * No SQL mocks, embedded database, balance-writing settlement fixture or shared-server startup.
 *
 * Terms below are EXPLICIT TEST TERMS, not RuleEngine/production bridge acceptance. Reward
 * configuration is explicitly empty: reward persistence/transactions are real, incentive policy
 * acceptance is NOT COVERED. Payment evidence is a test provider: row 19 / line 9's real historical
 * proof provider remains PENDING. External imports, unowned platform-expiry policy and actual
 * payment-channel delivery are NOT COVERED. Payouts here use the actual ledger payout route.
 * Ban/withdrawal eligibility is covered by existing WithdrawalLedgerMySqlTest, not claimed here.
 */
public class SettlementLifecycleMySqlTest {
    private static final Instant T=Instant.parse("2026-01-10T12:00:00Z");
    private static final AtomicLong IDS=new AtomicLong(97_100_000L);
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private TransactionTemplate tx;
    private CommissionCalculateServiceImpl accounts;
    private LedgerService ledger;
    private RewardService rewards;
    private SettlementExternalServices external;
    private DefaultSettlementService settlements;
    private long buyer,inviter;
    private boolean unknownHistory, failOutbox;
    private final TestTerms testTerms=new TestTerms();

    @BeforeClass public static void migrate() throws Exception { WithdrawalMySqlTest.migrateFreshRealMySqlDatabase(); }
    @AfterClass public static void cleanup() throws Exception { WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite(); }
    @Before public void wireRealServices() throws Exception {
        jdbc=(JdbcTemplate)ReflectionTestUtils.getField(WithdrawalMySqlTest.class,"jdbc");
        manager=(DataSourceTransactionManager)ReflectionTestUtils.getField(WithdrawalMySqlTest.class,"manager");
        tx=new TransactionTemplate(manager);
        buyer=IDS.incrementAndGet(); inviter=IDS.incrementAndGet();
        user(buyer); user(inviter);
        var factory=new SqlSessionFactoryBean(); factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionFlowMapper.xml"),
            new ClassPathResource("mapper/system/SysCommissionConfigMapper.xml"));
        var sqlFactory=factory.getObject(); sqlFactory.getConfiguration().addMapper(RewardProgressMapper.class);
        var sessions=new SqlSessionTemplate(sqlFactory);
        accounts=new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts,"orderCommissionMapper",sessions.getMapper(SysOrderCommissionMapper.class));
        ReflectionTestUtils.setField(accounts,"flowMapper",sessions.getMapper(SysCommissionFlowMapper.class));
        ReflectionTestUtils.setField(accounts,"commissionConfigMapper",sessions.getMapper(SysCommissionConfigMapper.class));
        ReflectionTestUtils.setField(accounts,"accountMapper",sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts,"ledgerJdbc",jdbc);
        ledger=transactional(new DefaultLedgerService(accounts),LedgerService.class);
        rewards=transactional(new RewardService(sessions.getMapper(RewardProgressMapper.class)),RewardService.class);
        PaymentInvitationSnapshotPort evidence=(platform,order,line,user,paidAt) -> unknownHistory ? Optional.empty()
            : Optional.of(new PaymentInvitationSnapshotPort.Snapshot(user,inviter,paidAt,"TEST-PROOF-PENDING-LINE9:"+order));
        var invitations=transactional(new PaymentInvitationSnapshotService(jdbc,provider(PaymentInvitationSnapshotPort.class,evidence)),
            PaymentInvitationSnapshotService.class);
        var outboxRepository=new SettlementNotificationRepository(jdbc) {
            @Override public void insert(String key,Long user,Long order,String reason,BigDecimal loss,BigDecimal balance) {
                super.insert(key,user,order,reason,loss,balance);
                if (failOutbox) throw new IllegalStateException("injected failure AFTER real outbox INSERT");
            }
        };
        var notifications=transactional(new SettlementNotificationService(outboxRepository,
            emptyProvider(SettlementNotificationChannel.class),manager),SettlementNotificationService.class);
        external=new SettlementExternalServices(invitations,notifications);
        settlements=service(ledger,provider(SettlementTermsPort.class,testTerms));
    }
    private DefaultSettlementService service(LedgerService funds,ObjectProvider<SettlementTermsPort> terms) {
        SettlementRewardTermsPort rewardTerms=(id,user,line,parent,unknown) -> new RewardApi.Snapshot(id,
            new RewardApi.OrderKey(line.platform().name(),line.orderNo(),line.subOrderNo()),user,parent,line.paidAt(),unknown,
            BigDecimal.ZERO,BigDecimal.ZERO,bd("100"),List.of());
        return new DefaultSettlementService(new SettlementRepository(jdbc),funds,terms,
            provider(SettlementFreezePort.class,new ConfiguredSettlementFreezePort()),external,rewards,
            provider(SettlementRewardTermsPort.class,rewardTerms),manager);
    }
    private <T> T transactional(T target,Class<T> type) {
        var advice=new TransactionInterceptor(); advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy=new ProxyFactory(target);
        if (type.isInterface()) proxy.setInterfaces(type); else proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice); return type.cast(proxy.getProxy());
    }
    private static <T> ObjectProvider<T> provider(Class<T> type,T value) {
        var beans=new StaticListableBeanFactory(); beans.addBean("explicit-test-provider",value); return beans.getBeanProvider(type);
    }
    private static <T> ObjectProvider<T> emptyProvider(Class<T> type) { return new StaticListableBeanFactory().getBeanProvider(type); }
    private void user(long id) {
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,?,'0','0')",id,"settlement-"+id,"Settlement test");
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)",id,"settlement-openid-"+id);
    }
    private long order() {
        long id=IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_order_commission(id,order_id,sub_order_no,platform_type,agent_user_id,buyer_user_id,order_amount,order_status) "
            +"VALUES (?,?,'line','tbk',?,?,100,'pending')",id,"settlement-order-"+id,buyer,buyer);
        return id;
    }
    private UnionOrderLine line(long id,String actual) {
        return new UnionOrderLine(Platform.TBK,"settlement-order-"+id,"line","item","pid",null,bd("100"),bd("10"),
            actual==null ? null : bd(actual),"REAL_ALLIANCE_TEST_PAYLOAD",T.minus(Duration.ofDays(3)),T,T);
    }
    private OrderEvent event(long id,String key,OrderStatus from,OrderStatus to,String recovered,Instant at) {
        return new OrderEvent(id+":"+key,id,line(id,"10"),from,to,null,recovered==null ? null : bd(recovered),"MYSQL_COMPONENT_TEST",id,at);
    }
    private void receive(long id) { settlements.onOrderEvent(event(id,"receive",null,OrderStatus.RECEIVED,null,T)); }
    private void refund(long id,String key,OrderStatus from,String amount,boolean full,Instant at) {
        settlements.onOrderEvent(event(id,key,from,full ? OrderStatus.FULL_REFUND : OrderStatus.PARTIAL_REFUND,amount,at));
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value).setScale(2); }
    private void money(String expected,BigDecimal actual) { assertNotNull(actual); assertEquals(expected,0,bd(expected).compareTo(actual)); }
    private BigDecimal account(long user,String field) {
        assertTrue(Set.of("balance","frozen_amount","withdrawn","withdrawing_amount").contains(field));
        List<BigDecimal> rows=jdbc.queryForList("SELECT "+field+" FROM sys_agent_commission_account WHERE user_id=?",BigDecimal.class,user);
        return rows.isEmpty() ? bd("0") : rows.getFirst();
    }
    private long count(String table,long id) {
        assertTrue(Set.of("cps_settle_item","cps_settlement_receipt","cps_settlement_position","cps_payment_invitation_snapshot",
            "cps_settlement_notification","cps_reward_order_snapshot").contains(table));
        String column=table.equals("cps_reward_order_snapshot") ? "order_id" : "order_line_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Long.class,id);
    }
    private long flows(long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=?",Long.class,id); }
    private BigDecimal item(long id,String type,String field) {
        assertTrue(Set.of("recovered_amount","final_amount","est_amount").contains(field));
        return jdbc.queryForObject("SELECT "+field+" FROM cps_settle_item WHERE order_line_id=? AND type=?",BigDecimal.class,id,type);
    }
    private void states(long id,String status) {
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND status<>?",Long.class,id,status));
        assertTrue(count("cps_settle_item",id)>0);
    }
    private void audit() {
        for (long user:new long[]{buyer,inviter}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_agent_commission_account WHERE user_id=?",Long.class,user)==0) continue;
            var result=new LedgerAuditService(jdbc).checkAfter(user-1,1).getFirst();
            assertEquals(user,result.userId()); assertTrue("cache must reconstruct from actual ledger",result.matches());
        }
    }
    private void payout(String key,String amount) {
        ledger.post(withdraw(key,"WITHDRAW_APPLY",AccountType.USER_AVAILABLE,AccountType.USER_WITHDRAWING,amount));
        ledger.post(withdraw(key,"WITHDRAW_SUCCESS",AccountType.USER_WITHDRAWING,AccountType.PAYOUT,amount));
    }
    private PostingCommand withdraw(String key,String type,AccountType debit,AccountType credit,String amount) {
        return new PostingCommand(type+":"+key,type,key,debit,buyer,null,"withdraw",debit,credit,bd(amount),"Settlement test payout route",null);
    }

    @Test public void estimateThenFirstObservedSettlementHasNoFabricatedPaidLedger() {
        long id=order();
        settlements.onOrderEvent(event(id,"paid",null,OrderStatus.PAID,null,T));
        assertEquals(0,flows(id)); states(id,"ESTIMATED");
        settlements.onOrderEvent(event(id,"settled",OrderStatus.PAID,OrderStatus.SETTLED,null,T.plusSeconds(1)));
        money("6",account(buyer,"frozen_amount")); money("2",account(inviter,"frozen_amount"));
        assertEquals(3,flows(id)); states(id,"FROZEN");
        assertEquals("SETTLED",jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?",String.class,id));
        money("10",jdbc.queryForObject("SELECT total_commission FROM sys_order_commission WHERE id=?",BigDecimal.class,id));
        money("2",jdbc.queryForObject("SELECT platform_amount FROM sys_order_commission WHERE id=?",BigDecimal.class,id));
        assertEquals(Long.valueOf(buyer),jdbc.queryForObject("SELECT agent_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        audit();
    }
    @Test public void frozenFullRefundVoidsRebateReferralAndSubsidyAtomically() {
        long id=order(); receive(id); refund(id,"full",OrderStatus.RECEIVED,"10",true,T.plusSeconds(1));
        assertEquals(3,count("cps_settle_item",id)); states(id,"VOID");
        money("5",item(id,"REBATE","recovered_amount")); money("2",item(id,"REFERRAL","recovered_amount")); money("1",item(id,"SUBSIDY","recovered_amount"));
        money("0",account(buyer,"frozen_amount")); money("0",account(inviter,"frozen_amount"));
        assertEquals(3,count("cps_settlement_notification",id)); assertEquals(2,count("cps_settlement_receipt",id));
        assertEquals(Long.valueOf(3),jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_notification WHERE order_line_id=? AND reason='REFUND' AND status='PENDING'",Long.class,id));
        audit();
    }
    @Test public void cumulativeAllThreeUsesCentTailAndExactReplayDoesNotPayTwice() {
        long id=order(); receive(id);
        OrderEvent partial=event(id,"partial",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"3.33",T.plusSeconds(1));
        settlements.onOrderEvent(partial);
        // Independent cent expectations: sorted buyer REBATE, buyer SUBSIDY, inviter REFERRAL.
        money("1.66",item(id,"REBATE","recovered_amount")); money("0.33",item(id,"SUBSIDY","recovered_amount")); money("0.67",item(id,"REFERRAL","recovered_amount"));
        long flowCount=flows(id),notifications=count("cps_settlement_notification",id);
        settlements.onOrderEvent(partial); assertEquals(flowCount,flows(id)); assertEquals(notifications,count("cps_settlement_notification",id));
        refund(id,"more",OrderStatus.PARTIAL_REFUND,"7",false,T.plusSeconds(2));
        money("3.50",item(id,"REBATE","recovered_amount")); money("0.70",item(id,"SUBSIDY","recovered_amount")); money("1.40",item(id,"REFERRAL","recovered_amount"));
        money("1.80",account(buyer,"frozen_amount")); money("0.60",account(inviter,"frozen_amount"));
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND (recovered_amount>final_amount OR fund_phase<>'FROZEN')",Long.class,id)); audit();
    }
    @Test public void creditedRefundCreatesDebtAndLaterOrderNaturallyOffsetsIt() {
        long id=order(); receive(id); settlements.unfreezeDue(T.plusSeconds(3601),1000); payout("debt-"+id,"6");
        refund(id,"full",OrderStatus.RECEIVED,"10",true,T.plusSeconds(4000));
        states(id,"FULLY_RECOVERED"); money("-6",account(buyer,"balance"));
        long next=order(); receive(next); settlements.unfreezeDue(T.plusSeconds(3601),1000);
        money("0",account(buyer,"balance"));
        long third=order(); receive(third); settlements.unfreezeDue(T.plusSeconds(3601),1000);
        money("6",account(buyer,"balance")); money("6",account(buyer,"withdrawn")); audit();
    }
    @Test public void payoutThenRefundCannotReverseOrDeleteSuccessfulPayout() {
        long id=order(); receive(id); settlements.unfreezeDue(T.plusSeconds(3601),1000); String key="paid-"+id; payout(key,"6");
        List<Map<String,Object>> before=jdbc.queryForList("SELECT * FROM sys_commission_flow WHERE business_id=? AND business_type='WITHDRAW_SUCCESS'",key);
        assertEquals(1,before.size()); refund(id,"full",OrderStatus.RECEIVED,"10",true,T.plusSeconds(4000));
        assertEquals(before,jdbc.queryForList("SELECT * FROM sys_commission_flow WHERE business_id=? AND business_type='WITHDRAW_SUCCESS'",key));
        money("6",account(buyer,"withdrawn")); money("0",account(buyer,"withdrawing_amount")); money("-6",account(buyer,"balance")); audit();
    }
    @Test public void disputePausesDurationAndOnlyRestoresRecordedOrderPhase() {
        long id=order(); receive(id);
        settlements.onOrderEvent(new OrderEvent(id+":dispute",id,line(id,"10"),OrderStatus.RECEIVED,OrderStatus.DISPUTED,OrderStatus.RECEIVED,null,"TEST",id,T.plusSeconds(600)));
        states(id,"PAUSED"); long before=flows(id); settlements.unfreezeDue(T.plusSeconds(7200),1000); assertEquals(before,flows(id));
        settlements.onOrderEvent(new OrderEvent(id+":bad",id,line(id,"10"),OrderStatus.DISPUTED,OrderStatus.SETTLED,OrderStatus.RECEIVED,null,"TEST",id,T.plusSeconds(1200)));
        assertEquals("DISPUTED",jdbc.queryForObject("SELECT order_status FROM cps_settlement_position WHERE order_line_id=?",String.class,id));
        assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=? AND event_key=?",Long.class,id,id+":bad"));
        settlements.onOrderEvent(new OrderEvent(id+":restore",id,line(id,"10"),OrderStatus.DISPUTED,OrderStatus.RECEIVED,OrderStatus.RECEIVED,null,"TEST",id,T.plusSeconds(1200)));
        assertEquals(T.plusSeconds(4200),jdbc.queryForObject("SELECT unfreeze_at FROM cps_settle_item WHERE order_line_id=? ORDER BY id LIMIT 1",java.sql.Timestamp.class,id).toInstant());
        settlements.unfreezeDue(T.plusSeconds(4199),1000); money("6",account(buyer,"frozen_amount"));
        settlements.unfreezeDue(T.plusSeconds(4200),1000); money("6",account(buyer,"balance")); audit();
    }
    @Test public void actualRecalculationUsesLockedVersionAndPreservesFullSnapshot() {
        long id=order(); settlements.onOrderEvent(event(id,"paid",null,OrderStatus.PAID,null,T));
        String frozenSnapshot=jdbc.queryForObject("SELECT terms_json FROM cps_settlement_position WHERE order_line_id=?",String.class,id);
        testTerms.liveVersion=999; // Live configuration changes must not replace the serialized version 7 snapshot.
        settlements.onOrderEvent(new OrderEvent(id+":settled",id,line(id,"20"),OrderStatus.PAID,OrderStatus.SETTLED,null,null,"TEST",id,T.plusSeconds(1)));
        assertEquals(frozenSnapshot,jdbc.queryForObject("SELECT terms_json FROM cps_settlement_position WHERE order_line_id=?",String.class,id));
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=? AND rule_version_id<>7",Long.class,id));
        money("5",item(id,"REBATE","est_amount")); money("10",item(id,"REBATE","final_amount"));
        money("12",account(buyer,"frozen_amount")); money("4",account(inviter,"frozen_amount")); audit();
    }
    @Test public void partialFrozenRemainderUnfreezesExactlyOnce() {
        long id=order(); receive(id); refund(id,"half",OrderStatus.RECEIVED,"5",false,T.plusSeconds(1));
        states(id,"PARTIALLY_RECOVERED"); settlements.unfreezeDue(T.plusSeconds(3601),1000);
        money("3",account(buyer,"balance")); money("1",account(inviter,"balance")); money("0",account(buyer,"frozen_amount"));
        long before=flows(id); settlements.unfreezeDue(T.plusSeconds(7200),1000); assertEquals(before,flows(id)); states(id,"CREDITED"); audit();
    }
    @Test public void unknownPaymentHistoryCreditsOnlyBuyerRebateNeverCurrentInviter() {
        unknownHistory=true; long id=order();
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=? WHERE user_id=?",inviter,buyer);
        receive(id); assertEquals(1,count("cps_settle_item",id)); money("5",account(buyer,"frozen_amount")); money("0",account(inviter,"frozen_amount"));
        assertEquals("MANUAL_REVIEW",jdbc.queryForObject("SELECT review_status FROM cps_payment_invitation_snapshot WHERE order_line_id=?",String.class,id));
        settlements.unfreezeDue(T.plusSeconds(3601),1000); money("5",account(buyer,"balance"));
        assertEquals(Long.valueOf(0),jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?",Long.class,inviter)); audit();
    }
    @Test public void duplicateEventPayloadMismatchRollsBackWithoutChangingAnyMoney() {
        long id=order(); receive(id); long before=flows(id);
        assertThrows(IllegalArgumentException.class,() -> settlements.onOrderEvent(new OrderEvent(id+":receive",id,line(id,"20"),null,OrderStatus.RECEIVED,null,null,"MYSQL_COMPONENT_TEST",id,T)));
        assertEquals(before,flows(id)); assertEquals(1,count("cps_settlement_receipt",id));
        money("6",account(buyer,"frozen_amount")); money("5",item(id,"REBATE","final_amount")); audit();
    }
    @Test public void outboxFailureRollsBackLedgerStateRecoveryAndReceiptTogetherThenRetryWorks() {
        long id=order(); receive(id); long before=flows(id); failOutbox=true;
        OrderEvent loss=event(id,"half",OrderStatus.RECEIVED,OrderStatus.PARTIAL_REFUND,"5",T.plusSeconds(1));
        assertThrows(IllegalStateException.class,() -> settlements.onOrderEvent(loss));
        assertEquals(before,flows(id)); assertEquals(0,count("cps_settlement_notification",id)); assertEquals(1,count("cps_settlement_receipt",id));
        money("0",item(id,"REBATE","recovered_amount")); money("6",account(buyer,"frozen_amount")); states(id,"FROZEN");
        assertEquals("RECEIVED",jdbc.queryForObject("SELECT line_status FROM sys_order_commission WHERE id=?",String.class,id));
        failOutbox=false; settlements.onOrderEvent(loss); assertEquals(3,count("cps_settlement_notification",id)); money("3",account(buyer,"frozen_amount")); audit();
    }
    @Test public void firstFundingFailureAlsoRollsBackInvitationRuleItemsAndRewardCapture() {
        long id=order(); LedgerService failAfterPosting=command -> { ledger.post(command); throw new IllegalStateException("after real ledger INSERT"); };
        DefaultSettlementService failing=service(failAfterPosting,provider(SettlementTermsPort.class,testTerms));
        assertThrows(IllegalStateException.class,() -> failing.onOrderEvent(event(id,"receive",null,OrderStatus.RECEIVED,null,T)));
        assertEquals(0,flows(id));
        for (String table:List.of("cps_settle_item","cps_settlement_receipt","cps_settlement_position","cps_payment_invitation_snapshot","cps_reward_order_snapshot")) assertEquals(table,0,count(table,id));
        money("0",account(buyer,"frozen_amount")); receive(id); money("6",account(buyer,"frozen_amount")); audit();
    }
    @Test public void missingTermsAdapterNeverFabricatesMoneyOrAcknowledgesEvent() {
        long id=order(); DefaultSettlementService missing=service(ledger,emptyProvider(SettlementTermsPort.class));
        assertThrows(IllegalStateException.class,() -> missing.onOrderEvent(event(id,"receive",null,OrderStatus.RECEIVED,null,T)));
        assertEquals(0,flows(id)); assertEquals(0,count("cps_settlement_receipt",id)); assertEquals(0,count("cps_payment_invitation_snapshot",id));
    }
    @Test public void legacyAlreadyAccountedOrderCannotBeCreditedByNewEngine() {
        long id=order(); jdbc.update("UPDATE sys_order_commission SET order_status='settled' WHERE id=?",id);
        receive(id); assertEquals(0,flows(id)); assertEquals(0,count("cps_settle_item",id));
        assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=? AND reason='LEGACY_ACCOUNTED_REQUIRES_MANUAL_MIGRATION'",Long.class,id));
    }
    @Test public void oldSettleAndRefundEntryPointsCannotMutateNewEngineOwnedOrder() {
        // Lead-owned legacy guard acceptance: this intentionally fails on branches without that guard.
        long id=order(); receive(id); settlements.unfreezeDue(T.plusSeconds(3601),1000); long before=flows(id);
        try { tx.execute(s -> accounts.settleOrder(id,bd("10"))); } catch (ServiceException rejected) { /* rejection or no-op are both safe */ }
        assertEquals("old settle must not double-credit",before,flows(id)); money("6",account(buyer,"balance"));
        try { tx.execute(s -> accounts.refundOrder(id)); } catch (ServiceException rejected) { /* same ownership boundary */ }
        assertEquals("old refund must not recover outside new engine",before,flows(id)); money("6",account(buyer,"balance")); audit();
    }

    /** Deterministic component-test adapter: never install as production rule configuration. */
    private class TestTerms implements SettlementTermsPort {
        long liveVersion=7;
        public Terms snapshot(long id,Long user,UnionOrderLine line,Long parent) {
            List<Benefit> benefits=new ArrayList<>(); benefits.add(new Benefit("REBATE",user,bd("5"),liveVersion));
            if (parent!=null) { benefits.add(new Benefit("SUBSIDY",user,bd("1"),liveVersion)); benefits.add(new Benefit("REFERRAL",parent,bd("2"),liveVersion)); }
            String freeze=JSON.toJSONString(new ConfiguredSettlementFreezePort.Configuration(
                Map.of(Platform.TBK,List.of(new ConfiguredSettlementFreezePort.Band(BigDecimal.ZERO,3600L))),Map.of(Platform.TBK,3600L),false));
            return new Terms(benefits,bd("100"),freeze,bd("2"),"{\"testOnly\":true,\"lockedVersion\":"+liveVersion+"}");
        }
        public Terms recalculate(Terms locked,UnionOrderLine line) {
            BigDecimal ratio=line.actualCommission().divide(bd("10"));
            return new Terms(locked.benefits().stream().map(b -> new Benefit(b.type(),b.beneficiaryUserId(),b.amount().multiply(ratio),b.ruleVersionId())).toList(),
                locked.maxCommission(),locked.freezePolicy(),locked.platformAmount().multiply(ratio),locked.ruleSnapshotJson());
        }
    }
}
