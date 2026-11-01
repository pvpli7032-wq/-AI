package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.InviteBindingMapper;
import com.ruoyi.system.domain.SysWechatUser;
import com.ruoyi.system.mapper.SysWechatUserMapper;
import com.ruoyi.system.service.impl.SysWechatUserServiceImpl;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL locks, unique keys, append-only invitation audit and payment-time snapshots. */
public class InviteBindingMySqlTest {
    private static String url, user, password, database, databaseUrl;
    private static JdbcTemplate jdbc;
    private static InviteBindingService service;
    private static SysWechatUserMapper users;
    private static TransactionTemplate transactions;
    private static final AtomicLong IDS = new AtomicLong(91000);

    @BeforeClass public static void setup() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line9_invite_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            assertEquals("MySQL", c.getMetaData().getDatabaseProductName());
            assertTrue(c.getMetaData().getDatabaseMajorVersion() >= 8);
            s.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int q = url.indexOf('?');
        String path = q < 0 ? url : url.substring(0, q);
        String options = q < 0 ? "" : url.substring(q);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        databaseUrl = (slash < 0 ? path : path.substring(0, slash)) + "/" + database + options
            + (options.contains("?") ? "&" : "?") + "connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        DriverManagerDataSource ds = new DriverManagerDataSource(databaseUrl, user, password);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(ds));
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setTypeAliasesPackage("com.ruoyi.system.domain");
        bean.setMapperLocations(new ClassPathResource("mapper/system/SysWechatUserMapper.xml"),
                new ClassPathResource("mapper/cps/InviteBindingMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(bean.getObject());
        users = session.getMapper(SysWechatUserMapper.class);
        InviteBindingService target = new InviteBindingService(session.getMapper(InviteBindingMapper.class), users);
        TransactionInterceptor tx = new TransactionInterceptor();
        tx.setTransactionManager(new DataSourceTransactionManager(ds));
        tx.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(tx);
        service = (InviteBindingService) proxy.getProxy();
    }

    @AfterClass public static void cleanup() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line9_invite_[a-f0-9]{32}"));
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Test public void ordinaryUserCodeBindsOnlyOnFirstAuthorization() {
        SysWechatUser inviter = register(null);
        SysWechatUser child = register(inviter.getInvitationCode());
        assertEquals(inviter.getUserId(), users.selectByUserId(child.getUserId()).getInviterUserId());
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_tbk_agent_apply WHERE user_id=?", Long.class, inviter.getUserId()));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit WHERE user_id=? AND event_type='FIRST_AUTH'", Long.class, child.getUserId()));
        ServiceException error = assertThrows(ServiceException.class,
                () -> service.registerFirstAuthorization(child, register(null).getInvitationCode()));
        assertTrue(error.getMessage().contains("首次授权"));
        assertEquals(inviter.getUserId(), users.selectByUserId(child.getUserId()).getInviterUserId());
    }

    @Test public void invalidInvitationStillCreatesUserWithoutInviter() {
        SysWechatUser child = register("99999999999999999999");
        assertNotNull(users.selectByOpenid(child.getOpenid()));
        assertNull(users.selectByUserId(child.getUserId()).getInviterUserId());
        assertEquals(Long.valueOf(1),jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit WHERE user_id=? AND event_type='FIRST_AUTH'",Long.class,child.getUserId()));
    }

    @Test public void simultaneousFirstAuthorizationsCannotReplaceInviter() throws Exception {
        SysWechatUser a = register(null), b = register(null);
        long childId = createSystemUser();
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> attempts = List.of(pool.submit(() -> attempt(childId, a.getInvitationCode(), ready, start)),
                    pool.submit(() -> attempt(childId, b.getInvitationCode(), ready, start)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : attempts) if (result.get(10, TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded);
            SysWechatUser child = users.selectByUserId(childId);
            assertTrue(child.getInviterUserId().equals(a.getUserId()) || child.getInviterUserId().equals(b.getUserId()));
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM sys_wechat_user WHERE user_id=?", Long.class, childId));
            assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit WHERE user_id=?", Long.class, childId));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test public void selfInvitationAndMissingAuditReasonAreRejected() {
        SysWechatUser child = register(null), inviter = register(null);
        assertTrue(assertThrows(ServiceException.class,
                () -> service.changeInviter(child.getUserId(), child.getUserId(), 1L, "self-test")).getMessage().contains("自己"));
        assertThrows(ServiceException.class, () -> service.changeInviter(child.getUserId(), inviter.getUserId(), 1L, " "));
        assertNull(users.selectByUserId(child.getUserId()).getInviterUserId());
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit WHERE user_id=?", Long.class, child.getUserId()));
    }

    @Test public void oldOrdersCannotBeUsedToCreateANewInvitationBinding() {
        SysWechatUser inviter = register(null);
        long childId = createSystemUser();
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,agent_user_id,buyer_user_id) VALUES (?,'tbk','',?,?)", "invite-existing-" + childId, childId, childId);
        assertThrows(ServiceException.class, () -> service.registerFirstAuthorization(wechat(childId), inviter.getInvitationCode()));
        assertNull(users.selectByUserId(childId));
    }

    @Test public void adminChangePreservesPaymentTimeInviterAndOrderSnapshot() {
        SysWechatUser a = register(null), b = register(null), child = register(a.getInvitationCode());
        Instant first = jdbc.queryForObject("SELECT created_at FROM cps_invite_binding_audit WHERE user_id=?", java.sql.Timestamp.class, child.getUserId()).toInstant();
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,agent_user_id,buyer_user_id,referrer_user_id) VALUES (?,'tbk','',?,?,?)", "invite-snapshot-" + child.getUserId(), child.getUserId(), child.getUserId(), a.getUserId());
        // Use an earlier, explicitly persisted fixture timestamp, so the assertion never depends on sleep/timing.
        jdbc.update("UPDATE cps_invite_binding_audit SET created_at=DATE_SUB(created_at, INTERVAL 1 SECOND) WHERE user_id=?", child.getUserId());
        Instant payment = first.minusMillis(500);
        assertEquals(a.getUserId(), service.resolveAt(child.getUserId(), payment));
        service.changeInviter(child.getUserId(), b.getUserId(), 1L, "人工核实后改绑");
        assertEquals(b.getUserId(), users.selectByUserId(child.getUserId()).getInviterUserId());
        assertEquals(a.getUserId(), service.resolveAt(child.getUserId(), payment));
        assertEquals(b.getUserId(), service.resolveAt(child.getUserId(), Instant.now().plusSeconds(1)));
        assertNull(service.resolveAt(child.getUserId(), first.minusSeconds(2)));
        assertEquals(a.getUserId(), jdbc.queryForObject("SELECT referrer_user_id FROM sys_order_commission WHERE order_id=?", Long.class, "invite-snapshot-" + child.getUserId()));
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit WHERE user_id=?", Long.class, child.getUserId()));
        assertEquals(a.getUserId(), jdbc.queryForObject("SELECT previous_inviter_user_id FROM cps_invite_binding_audit WHERE user_id=? AND event_type='ADMIN_CHANGE'", Long.class, child.getUserId()));
    }

    @Test public void staleProfileUpdatesCannotUndoAnAuditedChange() {
        SysWechatUser a = register(null), b = register(null), child = register(a.getInvitationCode());
        SysWechatUser stale = users.selectByUserId(child.getUserId());
        service.changeInviter(child.getUserId(), b.getUserId(), 1L, "人工改绑");
        SysWechatUserServiceImpl profiles = new SysWechatUserServiceImpl();
        ReflectionTestUtils.setField(profiles, "sysWechatUserMapper", users);
        stale.setNickname("profile-refresh");
        stale.setInvitationCode("untrusted-replacement-code");
        profiles.updateSysWechatUser(stale);
        SysWechatUser saved = users.selectByUserId(child.getUserId());
        assertEquals(b.getUserId(), saved.getInviterUserId());
        assertEquals(child.getInvitationCode(), saved.getInvitationCode());
        assertEquals("profile-refresh", saved.getNickname());
    }

    @Test public void creatingAnOwnCodeForALegacyUserDoesNotCreateAnInviter() {
        long id = createSystemUser();
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)", id, "invite-test-" + id);
        String code = service.ensureInvitationCode(id);
        assertTrue(code.matches("[1-9][0-9]{3,}"));
        assertEquals(code, service.ensureInvitationCode(id));
        assertNull(users.selectByUserId(id).getInviterUserId());
        assertNull(service.resolveAt(id, Instant.now()));
    }

    @Test public void invitationHistoryUsesUtcEvenWithANonUtcDatabaseSession() {
        SysWechatUser inviter = register(null);
        transactions.executeWithoutResult(status -> {
            jdbc.execute("SET SESSION time_zone = '+08:00'");
            assertEquals("+08:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
            SysWechatUser child = register(inviter.getInvitationCode());
            Instant stored = jdbc.queryForObject("SELECT created_at FROM cps_invite_binding_audit WHERE user_id=?", java.sql.Timestamp.class, child.getUserId()).toInstant();
            Instant payment = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", (row, index) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
            assertTrue("Audit Instant must round-trip independently of session time zone", Math.abs(payment.toEpochMilli() - stored.toEpochMilli()) < 5000);
            assertEquals(inviter.getUserId(), service.resolveAt(child.getUserId(), payment));
        });
    }

    @Test public void gmt8JdbcPreservesPaymentTimeBindingWithUtcAndGmt8Sessions() throws Exception {
        int query = databaseUrl.indexOf('?');
        String base = query < 0 ? databaseUrl : databaseUrl.substring(0, query);
        String options = query < 0 ? "" : Arrays.stream(databaseUrl.substring(query + 1).split("&"))
                .filter(option -> !option.matches("(?i)(serverTimezone|connectionTimeZone|forceConnectionTimeZoneToSession)=.*"))
                .collect(Collectors.joining("&"));
        String eastUrl = base + "?" + (options.isEmpty() ? "" : options + "&")
                + "serverTimezone=GMT%2B8&forceConnectionTimeZoneToSession=false";
        DriverManagerDataSource east = new DriverManagerDataSource(eastUrl, user, password);
        JdbcTemplate eastJdbc = new JdbcTemplate(east);
        InviteBindingService eastService = invitationService(east);
        TransactionTemplate eastTransactions = new TransactionTemplate(new DataSourceTransactionManager(east));
        SysWechatUser inviter = register(null);
        for (String sessionZone : List.of("+00:00", "+08:00")) {
            eastTransactions.executeWithoutResult(status -> {
                eastJdbc.execute("SET SESSION time_zone = '" + sessionZone + "'");
                assertEquals(sessionZone, eastJdbc.queryForObject("SELECT @@session.time_zone", String.class));
                SysWechatUser child = wechat(createSystemUser());
                eastService.registerFirstAuthorization(child, inviter.getInvitationCode());
                Instant recorded = eastJdbc.queryForObject("SELECT created_at FROM cps_invite_binding_audit WHERE user_id=?", java.sql.Timestamp.class, child.getUserId()).toInstant();
                Instant now = eastJdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", (row, index) -> row.getObject(1, LocalDateTime.class).toInstant(ZoneOffset.UTC));
                assertTrue("GMT+8 JDBC must not shift audit by eight hours for session " + sessionZone, Math.abs(now.toEpochMilli() - recorded.toEpochMilli()) < 5000);
                assertNull(eastService.resolveAt(child.getUserId(), recorded.minusMillis(1)));
                assertEquals(inviter.getUserId(), eastService.resolveAt(child.getUserId(), recorded));
                Instant payment = recorded.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
                eastJdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,agent_user_id,buyer_user_id,paid_time) VALUES (?,'tbk','',?,?,?)", "invite-gmt8-" + child.getUserId(), child.getUserId(), child.getUserId(), java.sql.Timestamp.from(payment));
                Instant orderPayment = eastJdbc.queryForObject("SELECT paid_time FROM sys_order_commission WHERE order_id=?", java.sql.Timestamp.class, "invite-gmt8-" + child.getUserId()).toInstant();
                assertEquals(payment, orderPayment);
                assertEquals(inviter.getUserId(), eastService.resolveAt(child.getUserId(), orderPayment));
            });
        }
    }

    private static InviteBindingService invitationService(DriverManagerDataSource source) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setTypeAliasesPackage("com.ruoyi.system.domain");
        bean.setMapperLocations(new ClassPathResource("mapper/system/SysWechatUserMapper.xml"), new ClassPathResource("mapper/cps/InviteBindingMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(bean.getObject());
        InviteBindingService target = new InviteBindingService(session.getMapper(InviteBindingMapper.class), session.getMapper(SysWechatUserMapper.class));
        TransactionInterceptor tx = new TransactionInterceptor();
        tx.setTransactionManager(new DataSourceTransactionManager(source));
        tx.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(tx);
        return (InviteBindingService) proxy.getProxy();
    }

    @Test public void paymentTimeResolutionReadsCommittedAuditAfterAnEarlierRepeatableRead() {
        SysWechatUser a = register(null), b = register(null), child = register(a.getInvitationCode());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            transactions.executeWithoutResult(status -> {
                // The order importer can already have a REPEATABLE_READ snapshot before attribution starts.
                jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit", Long.class);
                Future<?> change = pool.submit(() -> service.changeInviter(child.getUserId(), b.getUserId(), 1L, "并发人工核实"));
                try { change.get(5, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new AssertionError("Concurrent invitation change failed", ex); }
                assertEquals(b.getUserId(), service.resolveAt(child.getUserId(), Instant.now().plusSeconds(1)));
            });
        } finally { pool.shutdownNow(); }
    }

    @Test public void concurrentOwnCodeGenerationSurvivesTheLoginsEarlierReadSnapshot() {
        long id = createSystemUser();
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,?)", id, "invite-test-" + id);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> firstCode = new AtomicReference<>();
        try {
            transactions.executeWithoutResult(status -> {
                // The login controller reads the existing user before ensureInvitationCode acquires its lock.
                assertNull(users.selectByUserId(id).getInvitationCode());
                Future<String> concurrent = pool.submit(() -> service.ensureInvitationCode(id));
                try { firstCode.set(concurrent.get(5, TimeUnit.SECONDS)); }
                catch (Exception ex) { throw new AssertionError("Concurrent own-code generation failed", ex); }
                assertNotNull(firstCode.get());
                assertEquals(firstCode.get(), service.ensureInvitationCode(id));
            });
            assertEquals(firstCode.get(), users.selectByUserId(id).getInvitationCode());
            assertNull(users.selectByUserId(id).getInviterUserId());
            assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_invite_binding_audit WHERE user_id=?", Long.class, id));
        } finally { pool.shutdownNow(); }
    }

    @Test public void productionPaymentAdapterUsesAuditAtPaymentRatherThanCurrentInviter() {
        SysWechatUser a=register(null), b=register(null), child=register(a.getInvitationCode());
        String order="payment-port-"+child.getUserId();
        Instant payment=Instant.parse("2026-05-04T12:00:00Z");
        jdbc.update("UPDATE cps_invite_binding_audit SET created_at=? WHERE user_id=?",java.sql.Timestamp.from(payment.minusSeconds(60)),child.getUserId());
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,agent_user_id,buyer_user_id,paid_time) VALUES (?,'tbk','',?,?,?)",order,child.getUserId(),child.getUserId(),java.sql.Timestamp.from(payment));
        jdbc.update("UPDATE sys_order_commission SET cps_paid_second=?,cps_paid_nano=? WHERE order_id=?",payment.getEpochSecond(),payment.getNano(),order);
        service.changeInviter(child.getUserId(),b.getUserId(),1L,"verify payment-time evidence");
        var adapter=new AuditedPaymentInvitationAdapter(jdbc);
        var snapshot=adapter.findAtPayment(com.ruoyi.union.adapter.Platform.TBK,order,"",child.getUserId(),payment).orElseThrow();
        assertEquals(a.getUserId(),snapshot.inviterUserId());
        assertEquals(payment,snapshot.paidAt());
        assertTrue(snapshot.evidenceId().startsWith("invite-audit:"));
        assertTrue(adapter.findAtPayment(com.ruoyi.union.adapter.Platform.JD,order,"",child.getUserId(),payment).isEmpty());
        assertTrue(adapter.findAtPayment(com.ruoyi.union.adapter.Platform.TBK,order,"other",child.getUserId(),payment).isEmpty());
        assertTrue(adapter.findAtPayment(com.ruoyi.union.adapter.Platform.TBK,order,"",child.getUserId(),payment.plusSeconds(1)).isEmpty());
    }

    @Test public void productionPaymentAdapterDistinguishesKnownNoInviterFromUnknownLegacyHistory() {
        SysWechatUser child=register(null);
        String order="payment-null-"+child.getUserId();
        Instant payment=Instant.now().plusSeconds(3).truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,agent_user_id,buyer_user_id,paid_time) VALUES (?,'pdd','',?,?,?)",order,child.getUserId(),child.getUserId(),java.sql.Timestamp.from(payment));
        jdbc.update("UPDATE sys_order_commission SET cps_paid_second=?,cps_paid_nano=? WHERE order_id=?",payment.getEpochSecond(),payment.getNano(),order);
        var adapter=new AuditedPaymentInvitationAdapter(jdbc);
        var known=adapter.findAtPayment(com.ruoyi.union.adapter.Platform.PDD,order,null,child.getUserId(),payment).orElseThrow();
        assertNull(known.inviterUserId());
        assertTrue(known.evidenceId().startsWith("invite-audit:"));
        jdbc.update("UPDATE sys_order_commission SET cps_paid_second=NULL,cps_paid_nano=NULL WHERE order_id=?",order);
        assertTrue(adapter.findAtPayment(com.ruoyi.union.adapter.Platform.PDD,order,"",child.getUserId(),payment).isEmpty());
        jdbc.update("UPDATE sys_order_commission SET cps_paid_second=?,cps_paid_nano=? WHERE order_id=?",payment.getEpochSecond(),payment.getNano(),order);
        jdbc.update("UPDATE cps_invite_binding_audit SET event_type='LEGACY_BACKFILL' WHERE user_id=?",child.getUserId());
        assertTrue(adapter.findAtPayment(com.ruoyi.union.adapter.Platform.PDD,order,"",child.getUserId(),payment).isEmpty());
        jdbc.update("DELETE FROM cps_invite_binding_audit WHERE user_id=?",child.getUserId());
        assertTrue(adapter.findAtPayment(com.ruoyi.union.adapter.Platform.PDD,order,"",child.getUserId(),payment).isEmpty());
    }

    private static boolean attempt(long id, String code, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { service.registerFirstAuthorization(wechat(id), code); return true; }
        catch (ServiceException e) { assertTrue(e.getMessage().contains("首次授权")); return false; }
    }

    private static SysWechatUser register(String code) {
        SysWechatUser user = wechat(createSystemUser());
        service.registerFirstAuthorization(user, code);
        return user;
    }

    private static long createSystemUser() {
        long id = IDS.incrementAndGet();
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,?,?,'0','0')", id, "invite-test-" + id, "local-test");
        return id;
    }

    private static SysWechatUser wechat(long id) {
        SysWechatUser user = new SysWechatUser();
        user.setUserId(id);
        user.setOpenid("invite-test-" + id);
        return user;
    }
}
