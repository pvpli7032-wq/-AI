package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.constant.ScheduleConstants;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.mapper.SysJobMapper;
import com.ruoyi.quartz.service.ISysJobLogService;
import com.ruoyi.quartz.service.impl.SysJobServiceImpl;
import com.ruoyi.quartz.task.CpsAttributionTask;
import com.ruoyi.quartz.util.QuartzDisallowConcurrentExecution;
import com.ruoyi.quartz.util.ScheduleUtils;
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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.quartz.CronTrigger;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Real migration, backend schedule updates, Quartz invocation and InnoDB business effects. */
public class AttributionQuartzMySqlTest {
    private static final long USER = 92001L;
    private static String url, user, password, database;
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static SysJobMapper jobMapper;
    private static OrderAttributionService attribution;
    private static AttributionTokenService tokens;
    private static PidPoolService pids;
    private static final List<OrderEvent> deliveries = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean downstreamFails = new AtomicBoolean();
    private AnnotationConfigApplicationContext context;
    private Object previousBeanFactory, previousApplicationContext;
    private Scheduler scheduler;
    private SysJobServiceImpl jobs;
    private final BlockingQueue<SysJobLog> logs = new LinkedBlockingQueue<>();

    @BeforeClass public static void createDatabase() throws Exception {
        url = System.getenv("LINE4_MYSQL_URL");
        assertTrue("LINE4_MYSQL_URL with real MySQL 8 required", url != null && url.startsWith("jdbc:mysql://"));
        user = System.getenv().getOrDefault("LINE4_MYSQL_USER", "root");
        password = System.getenv().getOrDefault("LINE4_MYSQL_PASSWORD", "");
        database = "line9_quartz_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, user, password); Statement sql = connection.createStatement()) {
            assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getMetaData().getDatabaseMajorVersion() >= 8);
            sql.execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        }
        int query = url.indexOf('?');
        String path = query < 0 ? url : url.substring(0, query), options = query < 0 ? "" : url.substring(query);
        int slash = path.indexOf('/', "jdbc:mysql://".length());
        source = new DriverManagerDataSource((slash < 0 ? path : path.substring(0, slash)) + "/" + database + options, user, password);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.ruoyi.quartz.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/quartz/SysJobMapper.xml"),
            new ClassPathResource("mapper/cps/InviteBindingMapper.xml"));
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        jobMapper = session.getMapper(SysJobMapper.class);
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        AttributionSettings settings = new AttributionSettings(jdbc);
        pids = new PidPoolService(jdbc, settings, transactions);
        tokens = new AttributionTokenService(jdbc);
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("settlement", (SettlementService) event -> {
            deliveries.add(event);
            if (downstreamFails.get()) throw new IllegalStateException("synthetic downstream failure");
        });
        attribution = new OrderAttributionService(jdbc, pids, tokens, settings,
            new InviteBindingService(session.getMapper(InviteBindingMapper.class), null),
            beans.getBeanProvider(SettlementService.class), transactions);
        jdbc.update("UPDATE sys_config SET config_value='24' WHERE config_key IN ('cps.attribution.claim.hours','cps.attribution.item.match.hours')");
        jdbc.update("UPDATE sys_config SET config_value='30' WHERE config_key='cps.attribution.unclaimed.days'");
        jdbc.update("INSERT INTO sys_user(user_id,user_name,nick_name,status,del_flag) VALUES (?,'TEST_QUARTZ_USER','TEST USER','0','0')", USER);
        jdbc.update("INSERT INTO sys_wechat_user(user_id,openid) VALUES (?,'TEST_QUARTZ_OPENID')", USER);
        pids.register(Platform.TBK, "TEST_QUARTZ_DEFAULT", PidType.DEFAULT, true, 1L, "synthetic scheduler fixture");
    }

    @AfterClass public static void dropDatabase() throws Exception {
        if (database != null) {
            assertTrue(database.matches("line9_quartz_[a-f0-9]{32}"));
            try (Connection connection = DriverManager.getConnection(url, user, password); Statement sql = connection.createStatement()) {
                sql.execute("DROP DATABASE IF EXISTS `" + database + "`");
            }
        }
    }

    @Before public void setupScheduler() throws Exception {
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        previousApplicationContext = ReflectionTestUtils.getField(SpringUtils.class, "applicationContext");
        downstreamFails.set(false);
        deliveries.clear();
        ISysJobLogService logService = mock(ISysJobLogService.class);
        doAnswer(call -> { logs.add(call.getArgument(0)); return null; }).when(logService).addJobLog(any());
        context = new AnnotationConfigApplicationContext();
        context.registerBean(OrderAttributionService.class, () -> attribution);
        context.registerBean(AttributionTokenService.class, () -> tokens);
        context.registerBean(PidPoolService.class, () -> pids);
        context.registerBean(ISysJobLogService.class, () -> logService);
        context.register(AttributionQuartzOperations.class, CpsAttributionTask.class);
        context.refresh();
        SpringUtils bridge = new SpringUtils();
        bridge.postProcessBeanFactory(context.getBeanFactory());
        bridge.setApplicationContext(context);
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "line9-mysql-" + UUID.randomUUID());
        properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
        properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.setProperty("org.quartz.threadPool.threadCount", "2");
        properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(properties).getScheduler();
        jobs = new SysJobServiceImpl();
        ReflectionTestUtils.setField(jobs, "scheduler", scheduler);
        ReflectionTestUtils.setField(jobs, "jobMapper", jobMapper);
    }

    @After public void cleanupScheduler() throws Exception {
        try { if (scheduler != null) scheduler.shutdown(true); }
        finally {
            try { if (context != null) context.close(); }
            finally {
                ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
                ReflectionTestUtils.setField(SpringUtils.class, "applicationContext", previousApplicationContext);
            }
        }
    }

    @Test public void migratedJobsUseSafePoliciesAndBackendUpdatesTheirActualTriggers() throws Exception {
        List<SysJob> registered = attributionJobs();
        assertEquals(4, registered.size());
        jobs.init();
        int count = scheduler.getJobKeys(GroupMatcher.anyJobGroup()).size();
        jobs.init();
        assertEquals(count, scheduler.getJobKeys(GroupMatcher.anyJobGroup()).size());
        SysJob expiry = named(registered, "expireUnclaimed"), retry = named(registered, "retrySettlement");
        assertNotEquals(expiry.getJobId(), retry.getJobId());
        assertEquals("0 * * * * ?", expiry.getCronExpression());
        assertEquals("30 * * * * ?", retry.getCronExpression());
        for (SysJob job : registered) {
            assertEquals("DEFAULT", job.getJobGroup());
            assertEquals("0", job.getStatus());
            assertEquals("1", job.getConcurrent());
            assertEquals("3", job.getMisfirePolicy());
            assertTrue(job.getInvokeTarget().endsWith("(200)"));
            assertTrue(ScheduleUtils.whiteList(job.getInvokeTarget()));
            assertEquals(QuartzDisallowConcurrentExecution.class,
                scheduler.getJobDetail(ScheduleUtils.getJobKey(job.getJobId(), job.getJobGroup())).getJobClass());
            assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING, trigger(job).getMisfireInstruction());
        }

        expiry.setCronExpression("0 0/7 * * * ?");
        expiry.setInvokeTarget("cpsAttributionTask.expireUnclaimed(17)");
        assertEquals(1, jobs.updateJob(expiry));
        assertEquals(expiry.getCronExpression(), trigger(expiry).getCronExpression());
        assertEquals(expiry.getCronExpression(), jobMapper.selectJobById(expiry.getJobId()).getCronExpression());
        SysJob runtime = (SysJob) scheduler.getJobDetail(ScheduleUtils.getJobKey(expiry.getJobId(), expiry.getJobGroup()))
            .getJobDataMap().get(ScheduleConstants.TASK_PROPERTIES);
        assertEquals(expiry.getInvokeTarget(), runtime.getInvokeTarget());
        assertEquals(retry.getCronExpression(), trigger(retry).getCronExpression());
        assertEquals(1, jobs.pauseJob(expiry));
        assertEquals(Trigger.TriggerState.PAUSED, scheduler.getTriggerState(ScheduleUtils.getTriggerKey(expiry.getJobId(), expiry.getJobGroup())));

        // Reapplying the registration SQL must preserve an operator's cron, batch and paused state.
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V591__line9_attribution_quartz.sql")).execute(source);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V592__line9_evidence_cleanup_jobs.sql")).execute(source);
        assertEquals(4, attributionJobs().size());
        SysJob preserved = jobMapper.selectJobById(expiry.getJobId());
        assertEquals(expiry.getCronExpression(), preserved.getCronExpression());
        assertEquals(expiry.getInvokeTarget(), preserved.getInvokeTarget());
        assertEquals("1", preserved.getStatus());
        assertEquals(1, jobs.resumeJob(expiry));
        assertEquals(Trigger.TriggerState.NORMAL, scheduler.getTriggerState(ScheduleUtils.getTriggerKey(expiry.getJobId(), expiry.getJobGroup())));
    }

    @Test public void realQuartzExpiresOrdersAndRetriesTheSameOutboxEventWithoutDuplicateEffects() throws Exception {
        long expiredOrder = unclaimedOrder("TEST_QUARTZ_EXPIRE");
        jdbc.update("UPDATE cps_unclaimed_order SET expires_at=? WHERE order_line_id=?", Timestamp.from(AttributionData.now(jdbc).minusSeconds(1)), expiredOrder);
        long claimedOrder = unclaimedOrder("TEST_QUARTZ_RETRY");
        jdbc.update("UPDATE sys_order_commission SET line_status='SETTLED',settle_time=? WHERE id=?", Timestamp.from(AttributionData.now(jdbc)), claimedOrder);
        assertEquals("BOUND", attribution.claim(USER, Platform.TBK, "TEST_QUARTZ_RETRY").code());
        String eventKey = jdbc.queryForObject("SELECT event_key FROM cps_attribution_settlement_outbox WHERE order_line_id=?", String.class, claimedOrder);
        List<SysJob> registered = attributionJobs();
        for (SysJob job : registered) {
            // Trigger manually through the real backend service; keep wall-clock cron out of test timing.
            job.setCronExpression("0 0 0 1 1 ? 2099");
            job.setStatus("0");
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
        SysJob expiry = named(registered, "expireUnclaimed"), retry = named(registered, "retrySettlement");
        scheduler.start();
        downstreamFails.set(true);
        runAndAwait(retry);
        assertEquals("PENDING", outbox("status", claimedOrder));
        assertEquals("DELIVERY_FAILED", outbox("last_error", claimedOrder));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT attempts FROM cps_attribution_settlement_outbox WHERE order_line_id=?", Long.class, claimedOrder));
        runAndAwait(expiry);
        assertEquals("PLATFORM", jdbc.queryForObject("SELECT status FROM cps_unclaimed_order WHERE order_line_id=?", String.class, expiredOrder));
        assertNull(jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=?", Long.class, expiredOrder));

        downstreamFails.set(false);
        runAndAwait(retry);
        assertEquals("SENT", outbox("status", claimedOrder));
        assertEquals(eventKey, outbox("event_key", claimedOrder));
        runAndAwait(retry);
        runAndAwait(expiry);
        assertEquals(2, deliveries.size());
        assertTrue(deliveries.stream().allMatch(event -> eventKey.equals(event.eventKey())));
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT attempts FROM cps_attribution_settlement_outbox WHERE order_line_id=?", Long.class, claimedOrder));
        assertEquals(Long.valueOf(2), jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution WHERE order_line_id=?", Long.class, expiredOrder));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_settlement_outbox WHERE order_line_id=?", Long.class, claimedOrder));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow", Long.class));
    }

    @Test public void cleanupJobsRemoveOnlyEvidencePastItsRetentionPeriod() throws Exception {
        Instant now = AttributionData.now(jdbc);
        String oldToken = "a".repeat(32), retainedToken = "b".repeat(32);
        jdbc.update("INSERT INTO cps_attribution_token(token,platform,item_id,user_id,issued_at,expires_at) VALUES (?,'JD','TEST_ITEM',?,?,?)",
            oldToken, USER, Timestamp.from(now.minusSeconds(60L * 86400)), Timestamp.from(now.minusSeconds(40L * 86400)));
        jdbc.update("INSERT INTO cps_attribution_token(token,platform,item_id,user_id,issued_at,expires_at) VALUES (?,'JD','TEST_ITEM',?,?,?)",
            retainedToken, USER, Timestamp.from(now.minusSeconds(4L * 86400)), Timestamp.from(now.minusSeconds(2L * 86400)));
        long orphanLease = 987654321L;
        jdbc.update("INSERT INTO cps_pid_conversion_state(lease_id,published) VALUES (?,FALSE)", orphanLease);
        jdbc.update("INSERT INTO cps_pid_conversion_attempt(attempt_id,lease_id,created_at) VALUES (?,?,?)", "c".repeat(32), orphanLease, Timestamp.from(now));
        List<SysJob> registered = attributionJobs();
        for (String method : List.of("cleanupTokens", "cleanupReservations")) {
            SysJob job = named(registered, method);
            job.setCronExpression("0 0 0 1 1 ? 2099");
            job.setStatus("0");
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
        scheduler.start();
        runAndAwait(named(registered, "cleanupTokens"));
        runAndAwait(named(registered, "cleanupReservations"));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_token WHERE token=?", Long.class, oldToken));
        assertEquals(Long.valueOf(1), jdbc.queryForObject("SELECT COUNT(*) FROM cps_attribution_token WHERE token=?", Long.class, retainedToken));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_pid_conversion_state WHERE lease_id=?", Long.class, orphanLease));
        assertEquals(Long.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM cps_pid_conversion_attempt WHERE lease_id=?", Long.class, orphanLease));
    }

    private void runAndAwait(SysJob job) throws Exception {
        assertTrue(jobs.run(job));
        SysJobLog log = logs.poll(10, TimeUnit.SECONDS);
        assertNotNull("Quartz task should finish", log);
        assertEquals(job.getInvokeTarget(), log.getInvokeTarget());
        assertEquals("0", log.getStatus());
    }

    private static long unclaimedOrder(String order) {
        Instant paid = AttributionData.now(jdbc).minusSeconds(5);
        jdbc.update("INSERT INTO sys_order_commission(order_id,platform_type,sub_order_no,item_id,pid,paid_time,line_status,agent_user_id,order_amount,estimated_commission,actual_commission,raw_status) VALUES (?,'tbk','','TEST_QUARTZ_ITEM','TEST_QUARTZ_DEFAULT',?,'PAID',0,100,10,10,'test-status')", order, Timestamp.from(paid));
        long id = jdbc.queryForObject("SELECT id FROM sys_order_commission WHERE order_id=?", Long.class, order);
        assertNull(attribution.decide(id, new UnionOrderLine(Platform.TBK, order, "", "TEST_QUARTZ_ITEM", "TEST_QUARTZ_DEFAULT", null,
            new BigDecimal("100"), BigDecimal.TEN, BigDecimal.TEN, "test-status", paid, null, null)).userId());
        return id;
    }

    private CronTrigger trigger(SysJob job) throws Exception {
        return (CronTrigger) scheduler.getTrigger(ScheduleUtils.getTriggerKey(job.getJobId(), job.getJobGroup()));
    }

    private static List<SysJob> attributionJobs() {
        SysJob filter = new SysJob();
        filter.setInvokeTarget("cpsAttributionTask.");
        return jobMapper.selectJobList(filter);
    }

    private static SysJob named(List<SysJob> jobs, String method) {
        return jobs.stream().filter(job -> job.getInvokeTarget().startsWith("cpsAttributionTask." + method + "("))
            .findFirst().orElseThrow();
    }

    private static String outbox(String column, long order) {
        return jdbc.queryForObject("SELECT " + column + " FROM cps_attribution_settlement_outbox WHERE order_line_id=?", String.class, order);
    }
}
