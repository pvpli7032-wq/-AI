package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.mapper.InviteBindingMapper;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.Assert.*;

/** Acceptance tests run against real InnoDB locks and production Flyway migrations. */
public class AttributionMySqlTest {
    private static String url,user,password,database,databaseUrl;
    private static JdbcTemplate jdbc;
    private static AttributionSettings settings;
    private static PidPoolService pids;
    private static AttributionTokenService tokens;
    private static OrderAttributionService service;
    private static TransactionTemplate transactions;
    private static final List<OrderEvent> deliveries=Collections.synchronizedList(new ArrayList<>());
    private static final AtomicBoolean downstreamFails=new AtomicBoolean();
    private static final long A=89001L,B=89002L,C=89003L,D=89004L;

    @BeforeClass public static void setup() throws Exception {
        url=System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with real MySQL 8 required",url!=null && url.startsWith("jdbc:mysql://"));
        user=System.getenv().getOrDefault("LINE4_MYSQL_USER","root");
        password=System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD","");
        database="line9_attribution_"+UUID.randomUUID().toString().replace("-","");
        try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()) {
            assertEquals("MySQL",c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().getDatabaseMajorVersion()>=8);
            s.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int q=url.indexOf('?');String path=q<0?url:url.substring(0,q),options=q<0?"":url.substring(q);
        int slash=path.indexOf('/',"jdbc:mysql://".length());
        databaseUrl=(slash<0?path:path.substring(0,slash))+"/"+database+options;
        DriverManagerDataSource ds=new DriverManagerDataSource(databaseUrl,user,password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc=new JdbcTemplate(ds);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);
        transactions=new TransactionTemplate(manager);
        settings=new AttributionSettings(jdbc);pids=new PidPoolService(jdbc,settings,manager);tokens=new AttributionTokenService(jdbc);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/cps/InviteBindingMapper.xml"));
        InviteBindingService invites=new InviteBindingService(new SqlSessionTemplate(factory.getObject()).getMapper(InviteBindingMapper.class),null);
        StaticListableBeanFactory beans=new StaticListableBeanFactory();
        beans.addBean("settlement",(SettlementService)event -> {
            deliveries.add(event);
            if(downstreamFails.get()) throw new IllegalStateException("synthetic transient failure");
        });
        service=new OrderAttributionService(jdbc,pids,tokens,settings,invites,beans.getBeanProvider(SettlementService.class),manager);
        for(long id:new long[]{A,B,C,D}) {
            jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,?,'0','0')",id,"TEST_USER_"+id,"TEST USER");
            jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)",id,"TEST_OPENID_"+id);
        }
    }
    @AfterClass public static void cleanup() throws Exception {
        if(database!=null) {
            assertTrue(database.matches("line9_attribution_[a-f0-9]{32}"));
            try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()) {s.execute("DROP DATABASE IF EXISTS `"+database+"`");}
        }
    }
    @Before public void reset() {
        for(String table:List.of("cps_pid_conversion_attempt","cps_pid_conversion_state","cps_attribution_settlement_outbox","cps_attribution_inviter_snapshot","cps_attribution_candidate","cps_attribution_current","cps_attribution_review","cps_unclaimed_order","cps_attribution","cps_order_claim","cps_claim_mutex","cps_pid_lease","cps_pid_item_mutex","cps_pid_audit","cps_pid_registry","cps_attribution_token","cps_query_log","cps_settle_item","sys_commission_flow","sys_order_commission","cps_invite_binding_audit","sys_union_platform")) jdbc.update("DELETE FROM "+table);
        jdbc.update("UPDATE sys_config SET config_value='24' WHERE config_key='cps.attribution.claim.hours'");
        jdbc.update("UPDATE sys_config SET config_value='48' WHERE config_key='cps.attribution.lease.hours'");
        jdbc.update("UPDATE sys_config SET config_value='30' WHERE config_key='cps.attribution.unclaimed.days'");
        jdbc.update("UPDATE sys_config SET config_value='10' WHERE config_key='cps.attribution.settlement.max-attempts'");
        downstreamFails.set(false);deliveries.clear();
        for(Platform platform:Platform.values()) pids.register(platform,"TEST_"+platform+"_DEFAULT",PidType.DEFAULT,true,1L,"synthetic fixture");
    }

    @Test public void concurrentAllocationCannotLeaseSamePidAndItemToTwoUsers() throws Exception {
        pids.register(Platform.TBK,"TEST_TRACKING",PidType.TRACKING,true,1L,"fixture");
        List<PidAllocation> allocated=race(() -> pids.allocate(Platform.TBK,"item",A),() -> pids.allocate(Platform.TBK,"item",B));
        assertEquals(1,allocated.stream().filter(PidAllocation::tracking).count());
        assertEquals(1,allocated.stream().filter(a -> !a.tracking()).count());
        assertEquals(Long.valueOf(1),count("cps_pid_lease"));
        Long owner=jdbc.queryForObject("SELECT user_id FROM cps_pid_lease",Long.class);
        PidAllocation repeat=pids.allocate(Platform.TBK,"item",owner);
        assertTrue(repeat.tracking());assertEquals("TEST_TRACKING",repeat.pid());
        assertEquals(Long.valueOf(1),count("cps_pid_lease"));
        assertFalse(pids.allocate(Platform.TBK,"item",null).tracking());
        assertTrue(pids.allocate(Platform.TBK,"other-item",B).tracking());
        PidOccupancy usage=pids.occupancy(Platform.TBK,"item").get(0);
        assertEquals(1,usage.occupied());assertEquals(1,usage.capacity());assertEquals(0,BigDecimal.ONE.compareTo(usage.utilization()));
        jdbc.update("UPDATE cps_pid_lease SET expire_at=? WHERE item_id='item'",Timestamp.from(now().minusSeconds(1)));
        List<PidAllocation> reused=race(() -> pids.allocate(Platform.TBK,"item",C),() -> pids.allocate(Platform.TBK,"item",D));
        assertEquals(1,reused.stream().filter(PidAllocation::tracking).count());
        assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM cps_pid_lease WHERE item_id='item' AND expire_at>?",Long.class,Timestamp.from(now())));
        assertTrue(pids.allocate(Platform.TBK,"CaseSensitiveItem",A).tracking());
        assertTrue(pids.allocate(Platform.TBK,"casesensitiveitem",B).tracking());
    }

    @Test public void failedConversionReleasesOnlyAnUnpublishedLease() {
        pids.register(Platform.TBK,"TEST_TRACKING",PidType.TRACKING,true,1L,"fixture");
        PidConversionReservation failed=pids.reserveForConversion(Platform.TBK,"item",A);
        pids.finishConversion(failed,false);
        assertEquals(Long.valueOf(0),count("cps_pid_lease"));
        PidConversionReservation first=pids.reserveForConversion(Platform.TBK,"item",A);
        PidConversionReservation second=pids.reserveForConversion(Platform.TBK,"item",A);
        pids.finishConversion(first,false);
        assertEquals(Long.valueOf(1),count("cps_pid_lease"));
        pids.finishConversion(second,true);
        assertEquals(Long.valueOf(1),count("cps_pid_lease"));
        PidConversionReservation later=pids.reserveForConversion(Platform.TBK,"item",A);
        pids.finishConversion(later,false);
        assertEquals(Long.valueOf(1),count("cps_pid_lease"));
        assertEquals(Long.valueOf(0),count("cps_pid_conversion_attempt"));
        assertEquals(Long.valueOf(0),count("cps_pid_conversion_state"));
    }

    @Test public void leasesNeverCaptureOrdersBeforeStartAndExpiryIsExclusive() {
        pids.register(Platform.TBK,"TEST_TRACKING",PidType.TRACKING,true,1L,"fixture");
        pids.allocate(Platform.TBK,"item",A);
        Instant start=jdbc.queryForObject("SELECT start_at FROM cps_pid_lease",Timestamp.class).toInstant();
        Instant expiry=jdbc.queryForObject("SELECT expire_at FROM cps_pid_lease",Timestamp.class).toInstant();
        long before=order("before","",Platform.TBK,"item","TEST_TRACKING",null,start.minusMillis(1));
        assertNull(decide(before).userId());
        long beginning=order("beginning","",Platform.TBK,"item","TEST_TRACKING",null,start);
        assertEquals(Long.valueOf(A),decide(beginning).userId());
        long ending=order("ending","",Platform.TBK,"item","TEST_TRACKING",null,expiry);
        assertNull(decide(ending).userId());
    }

    @Test public void legacyOrderReferrerSurvivesWhenInvitationAuditIsMissing() {
        long id=order("legacy-inviter","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=? WHERE user_id=?",B,A);
        try {
            jdbc.update("UPDATE sys_order_commission SET buyer_user_id=?,agent_user_id=?,referrer_user_id=?,has_referrer='1' WHERE id=?",A,A,B,id);
            assertEquals(Long.valueOf(A),decide(id).userId());
            assertEquals(Long.valueOf(B),jdbc.queryForObject("SELECT referrer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
            assertEquals("1",jdbc.queryForObject("SELECT has_referrer FROM sys_order_commission WHERE id=?",String.class,id));
        } finally { jdbc.update("UPDATE sys_wechat_user SET inviter_user_id=NULL WHERE user_id=?",A); }
    }

    @Test public void defaultPidRemainsUnclaimedAndBlockedPidCannotBeClaimed() {
        long normal=order("default-order","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        assertEquals(AttributionMethod.UNATTRIBUTED,decide(normal).method());
        assertEquals("DEFAULT_PID",jdbc.queryForObject("SELECT reason FROM cps_unclaimed_order WHERE order_line_id=?",String.class,normal));
        pids.register(Platform.TBK,"TEST_BLOCKED",PidType.BLOCKED,false,1L,"fixture");
        long blocked=order("blocked-order","",Platform.TBK,"item","TEST_BLOCKED",null,now().minusSeconds(5));
        assertNull(decide(blocked).userId());
        assertEquals("BLOCKED_PID",service.claim(A,Platform.TBK,"blocked-order").code());
        assertThrows(ServiceException.class,() -> service.manualAssign(blocked,A,1L,"review"));
        assertThrows(ServiceException.class,() -> pids.register(Platform.TBK,"TEST_TBK_DEFAULT",PidType.TRACKING,true,1L,"must reject"));
        assertEquals(Long.valueOf(0),count("cps_pid_lease"));
    }

    @Test public void opaqueTokenIsBoundToUserPlatformItemAndIssueTime() {
        String token=tokens.issue(Platform.PDD,"item",A);
        Instant issued=jdbc.queryForObject("SELECT issued_at FROM cps_attribution_token",Timestamp.class).toInstant();
        long good=order("pdd-good","",Platform.PDD,"item","TEST_PDD_DEFAULT","{\"uid\":\""+token+"\"}",issued);
        assertEquals(Long.valueOf(A),decide(good).userId());
        long wrongItem=order("wrong-item","",Platform.PDD,"other","TEST_PDD_DEFAULT",token,issued);
        assertNull(decide(wrongItem).userId());
        long before=order("token-before","",Platform.PDD,"item","TEST_PDD_DEFAULT",token,issued.minusMillis(1));
        assertNull(decide(before).userId());
        long wrongPlatform=order("wrong-platform","",Platform.JD,"item","TEST_JD_DEFAULT",token,issued);
        assertNull(decide(wrongPlatform).userId());
        long forged=order("numeric-id","",Platform.PDD,"item","TEST_PDD_DEFAULT","{\"uid\":\""+A+"\"}",issued);
        assertNull(decide(forged).userId());
        assertNull(AttributionTokenService.normalize(Platform.PDD,"{\"uid\":\""+token+"\",\"pid\":\"forged\"}"));
        assertNull(AttributionTokenService.normalize(Platform.PDD,"{\"uid\":\"bad\",\"uid\":\""+token+"\"}"));
        assertNull(AttributionTokenService.normalize(Platform.PDD,"{\"uid\":\""+token+"\"}{}"));
    }

    @Test public void claimAgainstLeaseRequiresReviewAndPreservesOriginalOwner() {
        pids.register(Platform.TBK,"TEST_TRACKING",PidType.TRACKING,true,1L,"fixture");
        pids.allocate(Platform.TBK,"item",A);
        Instant start=jdbc.queryForObject("SELECT start_at FROM cps_pid_lease",Timestamp.class).toInstant();
        long id=order("priority-order","",Platform.TBK,"item","TEST_TRACKING",null,start);
        assertEquals(Long.valueOf(A),decide(id).userId());
        Map<String,Object> original=jdbc.queryForMap("SELECT * FROM cps_attribution WHERE order_line_id=?",id);
        assertEquals("MANUAL_REVIEW",service.claim(B,Platform.TBK,"priority-order").code());
        assertEquals(Long.valueOf(A),decide(id).userId());
        assertEquals(original,jdbc.queryForMap("SELECT * FROM cps_attribution WHERE id=?",original.get("id")));
        assertEquals(Long.valueOf(1),count("cps_attribution"));
        assertEquals(Long.valueOf(A),jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_review WHERE order_line_id=? AND status='PENDING'",Long.class,id));
    }

    @Test public void platformTokenCannotBeOverriddenByAnotherManualClaim() {
        String token=tokens.issue(Platform.JD,"item",A);
        Instant paid=jdbc.queryForObject("SELECT issued_at FROM cps_attribution_token",Timestamp.class).toInstant();
        long id=order("token-order","",Platform.JD,"item","TEST_JD_DEFAULT",token,paid);
        assertEquals(Long.valueOf(A),decide(id).userId());
        assertEquals("MANUAL_REVIEW",service.claim(B,Platform.JD,"token-order").code());
        assertEquals(Long.valueOf(A),decide(id).userId());
        assertEquals(Long.valueOf(1),count("cps_attribution"));
    }

    @Test public void concurrentClaimsForOneOrderEscalateAllChildrenToManualReview() throws Exception {
        jdbc.update("INSERT INTO cps_claim_mutex(platform,order_no) VALUES ('TBK','multi-child')");
        long a=order("multi-child","child-a",Platform.TBK,"item-a","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        long b=order("multi-child","child-b",Platform.TBK,"item-b","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(a);decide(b);
        List<ClaimResult> results=race(() -> service.claim(A,Platform.TBK,"multi-child"),() -> service.claim(B,Platform.TBK,"multi-child"));
        assertEquals(1,results.stream().filter(r -> "BOUND".equals(r.code())).count());
        assertEquals(1,results.stream().filter(r -> "MANUAL_REVIEW".equals(r.code())).count());
        Long originalOwner=jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?",Long.class,a);
        assertTrue(Objects.equals(originalOwner, A) || Objects.equals(originalOwner, B));
        for(long id:List.of(a,b)) {
            assertEquals(originalOwner,jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
            assertEquals(originalOwner,decide(id).userId());
            assertEquals("MANUAL_REVIEW",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,id));
        }
        assertEquals(Long.valueOf(2),jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_review WHERE status='PENDING'",Long.class));
        assertEquals(0,service.retrySettlement(100));
        assertTrue(deliveries.isEmpty());
        assertEquals(Long.valueOf(B),service.manualAssign(a,B,1L,"verified synthetic receipt").userId());
        assertEquals("CLAIMED",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,a));
    }

    @Test public void missingExternalRedPacketOrderNeverCreatesUnclaimedPoolAndPendingClaimCanBindLater() {
        ClaimResult missing=service.claim(A,Platform.TBK,"redpacket-missing");
        assertEquals("NOT_FOUND",missing.code());assertEquals(Long.valueOf(0),count("cps_unclaimed_order"));
        assertFalse(missing.maskedOrderNo().contains("redpacket"));
        assertTrue(missing.orderLineIds().isEmpty());
        service.claim(B,Platform.TBK,"arrives-later");
        long arrived=order("arrives-later","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        assertEquals(Long.valueOf(B),decide(arrived).userId());
        assertEquals(Long.valueOf(0),count("cps_unclaimed_order"));
        service.claim(A,Platform.TBK,"ancient-order");
        long ancient=order("ancient-order","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minus(2,ChronoUnit.DAYS));
        assertNull(decide(ancient).userId());
        assertEquals("EXPIRED",service.claim(A,Platform.TBK,"ancient-order").code());
    }

    @Test public void lateClaimQueuesStableSettlementEventAndRetriesFailureWithoutWritingMoney() {
        long id=order("late-order","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(id);
        jdbc.update("UPDATE sys_order_commission SET line_status='SETTLED',settle_time=? WHERE id=?",Timestamp.from(now()),id);
        assertEquals("BOUND",service.claim(A,Platform.TBK,"late-order").code());
        assertEquals("ALREADY_BOUND",service.claim(A,Platform.TBK,"late-order").code());
        assertEquals(Long.valueOf(1),count("cps_attribution_settlement_outbox"));
        downstreamFails.set(true);
        assertEquals(0,service.retrySettlement(100));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM cps_attribution_settlement_outbox",String.class));
        downstreamFails.set(false);
        assertEquals(1,service.retrySettlement(100));
        assertEquals(0,service.retrySettlement(100));
        assertEquals(2,deliveries.size());
        assertEquals(deliveries.get(0).eventKey(),deliveries.get(1).eventKey());
        assertEquals("ATTRIBUTION",deliveries.get(1).source());
        assertNull(deliveries.get(1).line().allianceStatusTime());
        assertEquals(Long.valueOf(0),count("sys_commission_flow"));
    }

    @Test public void unclaimedExpiryIsIdempotentPreservesHistoryAndRejectsLateClaim() {
        long id=order("expires","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(id);
        jdbc.update("UPDATE cps_unclaimed_order SET expires_at=? WHERE order_line_id=?",Timestamp.from(now().minusSeconds(1)),id);
        assertEquals(1,service.expireUnclaimed(100));assertEquals(0,service.expireUnclaimed(100));
        assertEquals("PLATFORM",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,id));
        assertEquals(Long.valueOf(2),count("cps_attribution"));
        assertNull(decide(id).userId());
        assertEquals("EXPIRED",service.claim(A,Platform.TBK,"expires").code());
        UnclaimedOrder view=service.unclaimed(Platform.TBK,"PLATFORM",100).get(0);
        assertEquals(id,view.orderLineId().longValue());assertEquals("****ires",view.maskedOrderNo());
    }

    @Test public void expiryContinuesPastAnInvalidOrderRow() {
        long bad=order("bad-row","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        long good=order("good-row","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(bad); decide(good);
        jdbc.update("UPDATE cps_unclaimed_order SET expires_at=? WHERE order_line_id IN (?,?)",Timestamp.from(now().minusSeconds(1)),bad,good);
        jdbc.update("UPDATE sys_order_commission SET platform_type='BAD' WHERE id=?",bad);
        assertEquals(1,service.expireUnclaimed(100));
        assertEquals("OPEN",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,bad));
        assertEquals("PLATFORM",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,good));
    }

    @Test public void settlementRetriesReachVisibleTerminalFailure() {
        jdbc.update("UPDATE sys_config SET config_value='2' WHERE config_key='cps.attribution.settlement.max-attempts'");
        long id=order("terminal-retry","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(id);
        assertEquals("BOUND",service.claim(A,Platform.TBK,"terminal-retry").code());
        downstreamFails.set(true);
        assertEquals(0,service.retrySettlement(100));
        assertEquals(0,service.retrySettlement(100));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM cps_attribution_settlement_outbox WHERE order_line_id=?",String.class,id));
        assertEquals("DELIVERY_FAILED",jdbc.queryForObject("SELECT last_error FROM cps_attribution_settlement_outbox WHERE order_line_id=?",String.class,id));
        assertEquals(1,service.failedSettlements(100).size());
        assertEquals(0,service.retrySettlement(100));
    }

    @Test public void tokenValidityAndCleanupRespectOrderDelayWindow() {
        String token=tokens.issue(Platform.JD,"item",A);
        Instant issued=jdbc.queryForObject("SELECT issued_at FROM cps_attribution_token WHERE token=?",Timestamp.class,token).toInstant();
        Instant expires=jdbc.queryForObject("SELECT expires_at FROM cps_attribution_token WHERE token=?",Timestamp.class,token).toInstant();
        assertEquals(Long.valueOf(A),tokens.resolve(Platform.JD,"item",token,issued));
        assertNull(tokens.resolve(Platform.JD,"item",token,expires));
        assertEquals(0,tokens.cleanupExpired(100));
        jdbc.update("UPDATE cps_attribution_token SET expires_at=? WHERE token=?",Timestamp.from(now().minus(31,ChronoUnit.DAYS)),token);
        assertEquals(1,tokens.cleanupExpired(100));
    }

    @Test public void alreadyPostedReassignmentRequiresAuditedLedgerReviewAndKeepsOwner() {
        pids.register(Platform.TBK,"TEST_TRACKING",PidType.TRACKING,true,1L,"fixture");pids.allocate(Platform.TBK,"item",A);
        Instant paid=jdbc.queryForObject("SELECT start_at FROM cps_pid_lease",Timestamp.class).toInstant();
        long id=order("posted-order","",Platform.TBK,"item","TEST_TRACKING",null,paid);decide(id);
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,order_commission_id) VALUES (?,'income_self',1,?)",A,id);
        assertEquals("MANUAL_REVIEW",service.claim(B,Platform.TBK,"posted-order").code());
        assertThrows(ServiceException.class,() -> service.manualAssign(id,C,1L,"manual transfer request"));
        assertEquals(Long.valueOf(A),jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        assertEquals(Long.valueOf(2),jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_review WHERE reason='POSTED_REASSIGNMENT' AND status='PENDING'",Long.class));
        assertEquals(Long.valueOf(1),count("sys_commission_flow"));
    }

    @Test public void lateAttributionSnapshotsInviterAtPaymentAndDoesNotFollowLaterChanges() {
        Instant paid=now().minusSeconds(10);
        auditInvite(A,C,paid.minusSeconds(10),"FIRST_AUTH");auditInvite(A,D,paid.plusSeconds(1),"ADMIN_CHANGE");
        long id=order("historical-inviter","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,paid);decide(id);
        assertEquals("BOUND",service.claim(A,Platform.TBK,"historical-inviter").code());
        assertEquals(Long.valueOf(C),jdbc.queryForObject("SELECT referrer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        auditInvite(A,B,now(),"ADMIN_CHANGE");
        decide(id);
        assertEquals(Long.valueOf(C),jdbc.queryForObject("SELECT referrer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        assertEquals(Long.valueOf(C),jdbc.queryForObject("SELECT s.inviter_user_id FROM cps_attribution_inviter_snapshot s JOIN cps_attribution_current c ON c.attribution_id=s.attribution_id WHERE c.order_line_id=?",Long.class,id));
    }

    @Test public void pendingClaimCapIsPerUserAndAllowsRetryOfExistingClaim() {
        jdbc.update("UPDATE sys_config SET config_value='1' WHERE config_key='cps.attribution.claim.max-pending'");
        try {
            assertEquals("NOT_FOUND",service.claimFromClient(A,Platform.TBK,"not-synced-1").code());
            assertEquals("NOT_FOUND",service.claimFromClient(A,Platform.TBK,"not-synced-1").code());
            assertThrows(ServiceException.class,() -> service.claimFromClient(A,Platform.TBK,"not-synced-2"));
            assertEquals("NOT_FOUND",service.claimFromClient(B,Platform.TBK,"not-synced-2").code());
        } finally { jdbc.update("UPDATE sys_config SET config_value='' WHERE config_key='cps.attribution.claim.max-pending'"); }
    }

    @Test public void missingClaimWindowFailsClosedInsteadOfInventingBusinessThreshold() {
        jdbc.update("UPDATE sys_config SET config_value='' WHERE config_key='cps.attribution.claim.hours'");
        assertThrows(ServiceException.class,() -> service.claim(A,Platform.TBK,"missing"));
        assertEquals(Long.valueOf(0),count("cps_order_claim"));
    }

    @Test public void orderSyncInsideOldRepeatableReadSnapshotStillSeesLatestClaim() throws Exception {
        long id=order("snapshot-order","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(id);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(tx -> {
                assertEquals(Long.valueOf(1),count("cps_attribution")); // establish an old consistent snapshot
                try {assertEquals("BOUND",worker.submit(() -> service.claim(B,Platform.TBK,"snapshot-order")).get(10,TimeUnit.SECONDS).code());}
                catch(Exception failed) {throw new AssertionError(failed);}
                assertEquals(Long.valueOf(B),decide(id).userId());
            });
            assertEquals(Long.valueOf(B),jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        } finally {worker.shutdownNow();}
    }

    @Test public void latePlatformTokenCannotRebindPlatformExpiredOrderOrEraseClaimConflict() {
        String token=tokens.issue(Platform.JD,"item",A);
        Instant paid=jdbc.queryForObject("SELECT issued_at FROM cps_attribution_token",Timestamp.class).toInstant();
        long expired=order("late-token-expiry","",Platform.JD,"item","TEST_JD_DEFAULT",null,paid);
        decide(expired);
        jdbc.update("UPDATE cps_unclaimed_order SET expires_at=? WHERE order_line_id=?",Timestamp.from(now().minusSeconds(1)),expired);
        assertEquals(1,service.expireUnclaimed(100));
        jdbc.update("UPDATE sys_order_commission SET attribution_token=? WHERE id=?",token,expired);
        assertNull(decide(expired).userId());
        assertEquals("PLATFORM",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,expired));
        long conflict=order("late-token-conflict","",Platform.JD,"item","TEST_JD_DEFAULT",null,paid);
        decide(conflict);
        assertEquals("BOUND",service.claim(A,Platform.JD,"late-token-conflict").code());
        assertEquals("MANUAL_REVIEW",service.claim(B,Platform.JD,"late-token-conflict").code());
        jdbc.update("UPDATE sys_order_commission SET attribution_token=? WHERE id=?",token,conflict);
        AttributionDecision after=decide(conflict);
        assertEquals(Long.valueOf(A),after.userId());assertEquals(AttributionMethod.CLAIM,after.method());
        assertEquals("MANUAL_REVIEW",jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?",String.class,conflict));
    }

    @Test public void itemCandidatesAreRecordedWithoutInventingSharedAttributionMethod() {
        Instant paid=now().minusSeconds(5);
        jdbc.update("INSERT INTO cps_query_log(user_id,platform,item_id,result,created_at) VALUES (?,'tbk','candidate-item','SUCCESS',?)",A,Timestamp.from(paid.minusSeconds(1)));
        long id=order("candidate-order","",Platform.TBK,"candidate-item","TEST_UNKNOWN",null,paid);
        AttributionDecision answer=decide(id);
        assertNull(answer.userId());assertEquals(AttributionMethod.UNATTRIBUTED,answer.method());
        assertEquals("ITEM_MATCH_CONTRACT_PENDING",jdbc.queryForObject("SELECT reason FROM cps_unclaimed_order WHERE order_line_id=?",String.class,id));
        assertEquals(Long.valueOf(A),jdbc.queryForObject("SELECT user_id FROM cps_attribution_candidate WHERE order_line_id=?",Long.class,id));
        decide(id);
        assertEquals(Long.valueOf(1),count("cps_attribution_candidate"));
    }

    @Test public void gmtEightDriverWithDifferentDatabaseSessionZonesPreservesLeaseAndTokenBoundaries() throws Exception {
        String zonedUrl=databaseUrl.contains("serverTimezone=")
            ? databaseUrl.replaceAll("serverTimezone=[^&]*","serverTimezone=GMT%2B8")
            : databaseUrl+(databaseUrl.contains("?")?"&":"?")+"serverTimezone=GMT%2B8";
        for(String sessionZone:List.of("+00:00","+08:00")) {
            DriverManagerDataSource ds=new DriverManagerDataSource(zonedUrl,user,password);
            Properties properties=new Properties();properties.setProperty("sessionVariables","time_zone='"+sessionZone+"'");
            ds.setConnectionProperties(properties);
            JdbcTemplate local=new JdbcTemplate(ds);
            assertEquals(sessionZone,local.queryForObject("SELECT @@session.time_zone",String.class));
            DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);
            AttributionSettings localSettings=new AttributionSettings(local);
            PidPoolService localPids=new PidPoolService(local,localSettings,manager);
            AttributionTokenService localTokens=new AttributionTokenService(local);
            SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
            factory.setMapperLocations(new ClassPathResource("mapper/cps/InviteBindingMapper.xml"));
            InviteBindingService invites=new InviteBindingService(new SqlSessionTemplate(factory.getObject()).getMapper(InviteBindingMapper.class),null);
            OrderAttributionService localService=new OrderAttributionService(local,localPids,localTokens,localSettings,invites,
                new StaticListableBeanFactory().getBeanProvider(SettlementService.class),manager);
            String item="zone-item-"+sessionZone;
            localPids.register(Platform.TBK,"TEST_ZONE_"+sessionZone,PidType.TRACKING,true,1L,"synthetic time zone");
            PidAllocation allocation=localPids.allocate(Platform.TBK,item,A);
            Instant start=local.queryForObject("SELECT start_at FROM cps_pid_lease WHERE item_id=?",Timestamp.class,item).toInstant();
            assertTrue("database clock must be a real Instant, not 8h earlier",Duration.between(Instant.now(),start).abs().getSeconds()<5);
            assertEquals(start.plus(48,ChronoUnit.HOURS),allocation.expiresAt());
            assertEquals(allocation.expiresAt(),localPids.allocate(Platform.TBK,item,A).expiresAt());
            for(int direction:new int[]{-1,1}) {
                Instant paid=start.plusMillis(direction);
                String order="zone-order-"+sessionZone+direction;
                local.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,item_id,pid,paid_time,line_status,agent_user_id) VALUES (?,'tbk','',?,?,?,'PAID',0)",order,item,allocation.pid(),Timestamp.from(paid));
                Long id=local.queryForObject("SELECT id FROM sys_order_commission WHERE order_id=?",Long.class,order);
                UnionOrderLine line=new UnionOrderLine(Platform.TBK,order,"",item,allocation.pid(),null,BigDecimal.ONE,null,null,"test",paid,null,null);
                assertEquals(direction<0?null:Long.valueOf(A),localService.decide(id,line).userId());
            }
            String token=localTokens.issue(Platform.JD,item,A);
            Instant issued=local.queryForObject("SELECT issued_at FROM cps_attribution_token WHERE token=?",Timestamp.class,token).toInstant();
            assertTrue(Duration.between(Instant.now(),issued).abs().getSeconds()<5);
            assertNull(localTokens.resolve(Platform.JD,item,token,issued.minusMillis(1)));
            assertEquals(Long.valueOf(A),localTokens.resolve(Platform.JD,item,token,issued.plusMillis(1)));
        }
    }

    @Test public void expiryRejectsClaimsAndLateTokenWithoutWaitingForMaintenance() {
        jdbc.update("UPDATE sys_config SET config_value='1440' WHERE config_key='cps.attribution.claim.hours'");
        String token=tokens.issue(Platform.JD,"item",A);
        Instant paid=jdbc.queryForObject("SELECT issued_at FROM cps_attribution_token WHERE token=?",Timestamp.class,token).toInstant();
        long claim=order("expiry-without-job-claim","",Platform.JD,"item","TEST_JD_DEFAULT",null,paid);
        long sync=order("expiry-without-job-sync","",Platform.JD,"item","TEST_JD_DEFAULT",null,paid);
        decide(claim);decide(sync);
        jdbc.update("UPDATE cps_unclaimed_order SET expires_at=?",Timestamp.from(now().minusSeconds(1)));
        assertEquals("EXPIRED",service.claim(A,Platform.JD,"expiry-without-job-claim").code());
        jdbc.update("UPDATE sys_order_commission SET attribution_token=? WHERE id=?",token,sync);
        assertNull(decide(sync).userId());
        assertEquals(Long.valueOf(2),jdbc.queryForObject("SELECT COUNT(*) FROM cps_unclaimed_order WHERE status='PLATFORM'",Long.class));
        assertEquals(Long.valueOf(0),count("cps_attribution_settlement_outbox"));
    }

    @Test public void postedClaimConflictIsVisibleAndPausesOutstandingSettlementDelivery() {
        long id=order("posted-conflict-queue","",Platform.TBK,"item","TEST_TBK_DEFAULT",null,now().minusSeconds(5));
        decide(id);
        assertEquals("BOUND",service.claim(A,Platform.TBK,"posted-conflict-queue").code());
        jdbc.update("INSERT INTO sys_commission_flow(user_id,flow_type,amount,order_commission_id) VALUES (?,'income_self',1,?)",A,id);
        assertEquals("MANUAL_REVIEW",service.claim(B,Platform.TBK,"posted-conflict-queue").code());
        assertEquals(Long.valueOf(A),jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?",Long.class,id));
        assertEquals(Long.valueOf(id),service.unclaimed(Platform.TBK,"MANUAL_REVIEW",100).get(0).orderLineId());
        assertEquals(0,service.retrySettlement(100));
        assertTrue(deliveries.isEmpty());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM cps_attribution_settlement_outbox",String.class));
        assertEquals("REVIEW_REQUIRED",jdbc.queryForObject("SELECT last_error FROM cps_attribution_settlement_outbox",String.class));
    }

    private static Instant now() {return AttributionData.now(jdbc);}
    private static Long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    private static void auditInvite(long person,long inviter,Instant time,String kind) {
        jdbc.update("INSERT INTO cps_invite_binding_audit(user_id,next_inviter_user_id,operator_id,event_type,reason,created_at) VALUES (?,?,1,?,'synthetic history',?)",person,inviter,kind,Timestamp.from(time));
    }
    private static long order(String order,String sub,Platform platform,String item,String pid,String token,Instant paid) {
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,item_id,pid,attribution_token,paid_time,line_status,agent_user_id,order_amount,estimated_commission,actual_commission,raw_status) VALUES (?,?,?,?,?,?,?,'PAID',0,100,10,10,'test-status')",order,platform.name().toLowerCase(),sub,item,pid,token,Timestamp.from(paid));
        return jdbc.queryForObject("SELECT id FROM sys_order_commission WHERE order_id=? AND sub_order_no=? AND platform_type=?",Long.class,order,sub,platform.name().toLowerCase());
    }
    private static AttributionDecision decide(long id) {
        UnionOrderLine line=jdbc.queryForObject("SELECT * FROM sys_order_commission WHERE id=?",(r,n)->new UnionOrderLine(Platform.valueOf(r.getString("platform_type").toUpperCase()),r.getString("order_id"),r.getString("sub_order_no"),r.getString("item_id"),r.getString("pid"),r.getString("attribution_token"),r.getBigDecimal("order_amount"),r.getBigDecimal("estimated_commission"),r.getBigDecimal("actual_commission"),r.getString("raw_status"),r.getTimestamp("paid_time").toInstant(),null,null),id);
        return service.decide(id,line);
    }
    private static <T> List<T> race(java.util.concurrent.Callable<T> a,java.util.concurrent.Callable<T> b) throws Exception {
        ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        try {
            Future<T> first=threads.submit(() -> {ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return a.call();});
            Future<T> second=threads.submit(() -> {ready.countDown();assertTrue(go.await(10,TimeUnit.SECONDS));return b.call();});
            assertTrue(ready.await(10,TimeUnit.SECONDS));go.countDown();return List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
        } finally {threads.shutdownNow();}
    }
}
