package com.ruoyi.system.cps.ledger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.service.ISysJobLogService;
import com.ruoyi.quartz.task.CpsSettlementUnfreezeTask;
import com.ruoyi.quartz.util.ScheduleUtils;
import com.ruoyi.system.cps.settlement.*;
import com.ruoyi.system.cps.withdraw.WithdrawalMySqlTest;
import com.ruoyi.system.mapper.SysAgentCommissionAccountMapper;
import com.ruoyi.system.service.impl.CommissionCalculateServiceImpl;

/** Real MySQL reports, locks, production ledger and Quartz invocation. */
public class LedgerAuditScheduleMySqlTest {
    private static final AtomicLong IDS = new AtomicLong(9966000000L);
    // Reserve a low ID range for a first-page fixture independently of JUnit method order.
    private static final AtomicLong FIRST_PAGE_IDS = new AtomicLong(1100000L);
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager manager;
    private LedgerAuditService auditor;
    private LedgerAuditMonitor monitor;
    private LedgerService ledger;
    private TransactionTemplate tx;

    @BeforeClass public static void migrate() throws Exception {
        WithdrawalMySqlTest.migrateFreshRealMySqlDatabase();
        jdbc = (JdbcTemplate) ReflectionTestUtils.getField(WithdrawalMySqlTest.class,"jdbc");
        manager = new DataSourceTransactionManager(jdbc.getDataSource());
    }
    @AfterClass public static void cleanup() throws Exception {
        WithdrawalMySqlTest.dropOnlyTheDatabaseCreatedByThisSuite();
    }
    @Before public void wire() throws Exception {
        auditor = new LedgerAuditService(jdbc);
        monitor = new LedgerAuditMonitor(jdbc,auditor,manager);
        tx = new TransactionTemplate(manager);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setMapperLocations(new ClassPathResource("mapper/system/SysAgentCommissionAccountMapper.xml"),
            new ClassPathResource("mapper/system/SysOrderCommissionMapper.xml"));
        var sessions = new SqlSessionTemplate(factory.getObject());
        var accounts = new CommissionCalculateServiceImpl();
        ReflectionTestUtils.setField(accounts,"accountMapper",sessions.getMapper(SysAgentCommissionAccountMapper.class));
        ReflectionTestUtils.setField(accounts,"ledgerJdbc",jdbc);
        ledger = new DefaultLedgerService(accounts);
    }

    @Test public void paginatedAuditPreservesMoneyAndFlowsAndRearmsRecoveredAlert() {
        List<Object[]> emptyAccounts = new ArrayList<>();
        for(int i=0;i<251;i++) emptyAccounts.add(new Object[]{IDS.incrementAndGet()});
        jdbc.batchUpdate("INSERT INTO sys_agent_commission_account(user_id,status) VALUES (?,'0')",emptyAccounts);
        long user=fundedUser();
        var flows=jdbc.queryForList("SELECT * FROM sys_commission_flow WHERE user_id=? ORDER BY id",user);
        // Controlled projection corruption in a disposable schema is the audit input.
        jdbc.update("UPDATE sys_agent_commission_account SET balance=balance+0.01 WHERE user_id=?",user);
        var first=monitor.audit(key("page"));
        assertTrue(first.checkedAccounts()>=252);
        var difference=jdbc.queryForMap("SELECT * FROM cps_ledger_audit_difference WHERE run_id=? AND user_id=?",first.runId(),user);
        assertMoney("5.00",(BigDecimal)difference.get("expected_available"));
        assertMoney("8.00",(BigDecimal)difference.get("expected_frozen"));
        assertMoney("3.00",(BigDecimal)difference.get("expected_withdrawing"));
        assertMoney("4.00",(BigDecimal)difference.get("expected_paid"));
        assertMoney("5.01",(BigDecimal)difference.get("recorded_available"));
        assertMoney("5.01",balance(user));
        assertEquals(flows,jdbc.queryForList("SELECT * FROM sys_commission_flow WHERE user_id=? ORDER BY id",user));
        assertEquals(1L,alerts(LedgerAuditMonitor.MISMATCH,"account:"+user));
        monitor.audit(key("still"));
        assertEquals(1L,alerts(LedgerAuditMonitor.MISMATCH,"account:"+user));
        // Undo only test-injected corruption. The monitor has no repair operation.
        jdbc.update("UPDATE sys_agent_commission_account SET balance=balance-0.01 WHERE user_id=?",user);
        monitor.audit(key("recovered"));
        assertEquals(0,active(LedgerAuditMonitor.MISMATCH,"account:"+user));
        jdbc.update("UPDATE sys_agent_commission_account SET balance=balance+0.02 WHERE user_id=?",user);
        monitor.audit(key("new-incident"));
        assertEquals(2L,alerts(LedgerAuditMonitor.MISMATCH,"account:"+user));
        jdbc.update("UPDATE sys_agent_commission_account SET balance=balance-0.02 WHERE user_id=?",user);
        monitor.audit(key("clean"));
        assertMoney("5.00",balance(user));
        assertEquals(flows,jdbc.queryForList("SELECT * FROM sys_commission_flow WHERE user_id=? ORDER BY id",user));
    }

    @Test public void concurrentSameAuditKeyCreatesOneReportAndOneOperationalAlarm() throws Exception {
        long user=fundedUser();
        jdbc.update("UPDATE sys_agent_commission_account SET frozen_amount=frozen_amount+0.01 WHERE user_id=?",user);
        String request=key("parallel");
        var executor=Executors.newFixedThreadPool(2);
        var start=new CountDownLatch(1);
        try {
            Future<LedgerAuditMonitor.Report> first=executor.submit(()->{start.await();return monitor.audit(request);});
            Future<LedgerAuditMonitor.Report> second=executor.submit(()->{start.await();return monitor.audit(request);});
            start.countDown();
            assertEquals(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_run WHERE audit_key=?",Long.class,request).longValue());
            assertEquals(1L,alerts(LedgerAuditMonitor.MISMATCH,"account:"+user));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));
            jdbc.update("UPDATE sys_agent_commission_account SET frozen_amount=frozen_amount-0.01 WHERE user_id=?",user);
        }
    }

    @Test public void failedAuditRollsBackPartialReportAndLeavesRetryableFailureAlarm() {
        addEmptyAccounts(251);
        long user=fundedUser(FIRST_PAGE_IDS.incrementAndGet());
        String subject="account:"+user, request=key("failure-after-first-page");
        var pages=new AtomicInteger();
        var failing=new LedgerAuditService(jdbc) {
            @Override public List<BalanceCheck> checkAfter(long after,int limit) {
                if (pages.incrementAndGet()>1) {
                    // These rows are visible inside the active scan transaction. The failure
                    // is deliberately AFTER real difference/state/alarm writes on page one.
                    Long run=jdbc.queryForObject("SELECT id FROM cps_ledger_audit_run WHERE audit_key=?",Long.class,request);
                    assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_difference "
                        +"WHERE run_id=? AND user_id=?",Long.class,run,user).longValue());
                    assertEquals(1L,alerts(LedgerAuditMonitor.MISMATCH,subject));
                    assertEquals(1,active(LedgerAuditMonitor.MISMATCH,subject));
                    throw new IllegalStateException("synthetic-private-database-error");
                }
                List<BalanceCheck> page=super.checkAfter(after,limit);
                assertEquals(250,page.size());
                assertTrue("the controlled mismatch must be on page one",
                    page.stream().anyMatch(check->check.userId()==user && !check.matches()));
                return page;
            }
        };
        var badMonitor=new LedgerAuditMonitor(jdbc,failing,manager);
        jdbc.update("UPDATE sys_agent_commission_account SET balance=balance+0.01 WHERE user_id=?",user);
        try {
            assertThrows(IllegalStateException.class,()->badMonitor.audit(request));
            assertEquals(2,pages.get());
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_run WHERE audit_key=?",Long.class,request).longValue());
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_difference WHERE user_id=?",Long.class,user).longValue());
            assertEquals(0L,alerts(LedgerAuditMonitor.MISMATCH,subject));
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert_state WHERE kind=? AND subject=?",
                Long.class,LedgerAuditMonitor.MISMATCH,subject).longValue());
            assertEquals(1,active(LedgerAuditMonitor.FAILED,"ledger"));
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE message LIKE '%synthetic-private%'",Long.class).longValue());
            assertMoney("5.01",balance(user));

            var recovered=monitor.audit(request);
            assertTrue(recovered.checkedAccounts()>=252);
            assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_difference WHERE run_id=? AND user_id=?",
                Long.class,recovered.runId(),user).longValue());
            assertEquals(1L,alerts(LedgerAuditMonitor.MISMATCH,subject));
            assertEquals(0,active(LedgerAuditMonitor.FAILED,"ledger"));
            assertMoney("5.01",balance(user)); // reports never repair a money projection
        } finally {
            jdbc.update("UPDATE sys_agent_commission_account SET balance=balance-0.01 WHERE user_id=?",user);
            monitor.audit(key("failure-fixture-cleanup"));
        }
    }

    @Test public void ledgerCommitBetweenAuditPagesUsesOneSnapshotAndNextAuditSeesNewMoney() throws Exception {
        addEmptyAccounts(251);
        long user=fundedUser(); // follows at least 251 newly inserted accounts
        var firstPageRead=new CountDownLatch(1);
        var continueScan=new CountDownLatch(1);
        var pages=new AtomicInteger();
        var oldView=new AtomicReference<LedgerAuditService.BalanceCheck>();
        var gated=new LedgerAuditService(jdbc) {
            @Override public List<BalanceCheck> checkAfter(long after,int limit) {
                List<BalanceCheck> page=super.checkAfter(after,limit);
                if (pages.incrementAndGet()==1) {
                    assertEquals(250,page.size());
                    assertFalse("target must be read from a later page",
                        page.stream().anyMatch(check->check.userId()==user));
                    firstPageRead.countDown();
                    try {
                        assertTrue("ledger commit gate timed out",continueScan.await(20,TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                    }
                }
                page.stream().filter(check->check.userId()==user).findFirst().ifPresent(oldView::set);
                return page;
            }
        };
        var snapshotMonitor=new LedgerAuditMonitor(jdbc,gated,manager);
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<LedgerAuditMonitor.Report> running=executor.submit(()->snapshotMonitor.audit(key("rr-old")));
            assertTrue("audit must read its first page",firstPageRead.await(20,TimeUnit.SECONDS));
            // This is a second committed transaction using the real ledger while the
            // scanner's RR transaction remains open; no financial mapper is mocked.
            post(user,"between-audit-pages","AUDIT_RR_RELEASE",AccountType.USER_FROZEN,AccountType.USER_AVAILABLE,"1.00");
            assertMoney("6.00",balance(user));
            assertEquals(6L,jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE user_id=?",Long.class,user).longValue());
            continueScan.countDown();
            var first=running.get(30,TimeUnit.SECONDS);
            assertTrue(pages.get()>1);
            var previous=oldView.get(); assertNotNull(previous); assertTrue(previous.matches());
            assertMoney("5.00",previous.available()); assertMoney("5.00",previous.recordedAvailable());
            assertMoney("8.00",previous.frozen()); assertMoney("8.00",previous.recordedFrozen());
            assertMoney("3.00",previous.withdrawing()); assertMoney("4.00",previous.paid());
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_difference WHERE run_id=? AND user_id=?",
                Long.class,first.runId(),user).longValue());

            var newView=new AtomicReference<LedgerAuditService.BalanceCheck>();
            var observer=new LedgerAuditService(jdbc) {
                @Override public List<BalanceCheck> checkAfter(long after,int limit) {
                    List<BalanceCheck> page=super.checkAfter(after,limit);
                    page.stream().filter(check->check.userId()==user).findFirst().ifPresent(newView::set);
                    return page;
                }
            };
            var next=new LedgerAuditMonitor(jdbc,observer,manager).audit(key("rr-new"));
            var current=newView.get(); assertNotNull(current); assertTrue(current.matches());
            assertMoney("6.00",current.available()); assertMoney("6.00",current.recordedAvailable());
            assertMoney("7.00",current.frozen()); assertMoney("7.00",current.recordedFrozen());
            assertMoney("3.00",current.withdrawing()); assertMoney("4.00",current.paid());
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM cps_ledger_audit_difference WHERE run_id=? AND user_id=?",
                Long.class,next.runId(),user).longValue());
            assertEquals(0L,alerts(LedgerAuditMonitor.MISMATCH,"account:"+user));
        } finally {
            continueScan.countDown();
            executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS));
        }
    }

    @Test public void actualHourlyQuartzTaskAuditsAndNotifiesDespiteUnfreezeFailure() throws Exception {
        long user=fundedUser();
        jdbc.update("UPDATE sys_agent_commission_account SET balance=balance+0.01 WHERE user_id=?",user);
        // Only isolate the other stages for this audit failure-injection test.
        var settlements=mock(DefaultSettlementService.class);
        doThrow(new IllegalStateException("synthetic unfreeze failure")).when(settlements).unfreezeDue(any(Instant.class),eq(1000));
        var notifications=mock(SettlementNotificationService.class);
        var task=new CpsSettlementUnfreezeTask(new SettlementMaintenanceJob(settlements,notifications,monitor));
        var logs=mock(ISysJobLogService.class);
        var completed=new CountDownLatch(1);
        var result=new AtomicReference<SysJobLog>();
        doAnswer(invocation->{result.set(invocation.getArgument(0));completed.countDown();return null;})
            .when(logs).addJobLog(any(SysJobLog.class));
        ConfigurableListableBeanFactory old=(ConfigurableListableBeanFactory)ReflectionTestUtils.getField(SpringUtils.class,"beanFactory");
        var beans=new DefaultListableBeanFactory();
        beans.registerSingleton("cpsSettlementUnfreezeTask",task);
        beans.registerSingleton("auditTestJobLogService",logs);
        new SpringUtils().postProcessBeanFactory(beans);
        Properties properties=new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName","audit-test-"+UUID.randomUUID());
        properties.setProperty("org.quartz.threadPool.threadCount","1");
        properties.setProperty("org.quartz.jobStore.class","org.quartz.simpl.RAMJobStore");
        var scheduler=new StdSchedulerFactory(properties).getScheduler();
        try {
            SysJob job=jdbc.queryForObject("SELECT * FROM sys_job WHERE invoke_target='cpsSettlementUnfreezeTask.run()'",
                (r,n)->{
                    SysJob j=new SysJob();
                    j.setJobId(r.getLong("job_id"));j.setJobName(r.getString("job_name"));
                    j.setJobGroup(r.getString("job_group"));j.setInvokeTarget(r.getString("invoke_target"));
                    j.setCronExpression(r.getString("cron_expression"));j.setMisfirePolicy(r.getString("misfire_policy"));
                    j.setConcurrent(r.getString("concurrent"));j.setStatus(r.getString("status"));return j;
                });
            assertEquals("0 0 * * * ?",job.getCronExpression());
            assertTrue(ScheduleUtils.whiteList(job.getInvokeTarget()));
            ScheduleUtils.createScheduleJob(scheduler,job);
            // Keep the migrated hourly cron and real job, but prevent an exact-hour
            // automatic firing from racing this test's explicit Quartz trigger.
            scheduler.pauseTrigger(ScheduleUtils.getTriggerKey(job.getJobId(),job.getJobGroup()));
            scheduler.start();
            scheduler.triggerJob(ScheduleUtils.getJobKey(job.getJobId(),job.getJobGroup()));
            assertTrue("actual Quartz job must finish",completed.await(20,TimeUnit.SECONDS));
            verify(notifications).retryPending(100);
            assertEquals("1",result.get().getStatus());
            assertEquals(1L,alerts(LedgerAuditMonitor.MISMATCH,"account:"+user));
            assertMoney("5.01",balance(user));
        } finally {
            scheduler.shutdown(true);
            ReflectionTestUtils.setField(SpringUtils.class,"beanFactory",old);
            jdbc.update("UPDATE sys_agent_commission_account SET balance=balance-0.01 WHERE user_id=?",user);
        }
    }

    private void addEmptyAccounts(int count) {
        List<Object[]> rows=new ArrayList<>();
        for(int i=0;i<count;i++) rows.add(new Object[]{IDS.incrementAndGet()});
        jdbc.batchUpdate("INSERT INTO sys_agent_commission_account(user_id,status) VALUES (?,'0')",rows);
    }
    private long fundedUser() { return fundedUser(IDS.incrementAndGet()); }
    private long fundedUser(long user) {
        post(user,"freeze","AUDIT_FREEZE",AccountType.REBATE_COST,AccountType.USER_FROZEN,"20.00");
        post(user,"release","AUDIT_RELEASE",AccountType.USER_FROZEN,AccountType.USER_AVAILABLE,"12.00");
        post(user,"paid","WITHDRAW_APPLY",AccountType.USER_AVAILABLE,AccountType.USER_WITHDRAWING,"4.00");
        post(user,"paid","WITHDRAW_SUCCESS",AccountType.USER_WITHDRAWING,AccountType.PAYOUT,"4.00");
        post(user,"pending","WITHDRAW_APPLY",AccountType.USER_AVAILABLE,AccountType.USER_WITHDRAWING,"3.00");
        return user;
    }
    private void post(long user,String identity,String type,AccountType debit,AccountType credit,String amount) {
        String businessId=identity+":"+user;
        tx.executeWithoutResult(s->ledger.post(new PostingCommand(type+":"+businessId,type,businessId,debit,user,
            null,"income_self",debit,credit,new BigDecimal(amount),"synthetic audit fixture",null)));
    }
    private static String key(String type) {return "audit-test:"+type+":"+UUID.randomUUID();}
    private static BigDecimal balance(long user) {
        return jdbc.queryForObject("SELECT balance FROM sys_agent_commission_account WHERE user_id=?",BigDecimal.class,user);
    }
    private static long alerts(String kind,String subject) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM cps_health_alert WHERE kind=? AND subject=?",Long.class,kind,subject);
    }
    private static int active(String kind,String subject) {
        return jdbc.queryForObject("SELECT condition_active FROM cps_health_alert_state WHERE kind=? AND subject=?",Integer.class,kind,subject);
    }
    private static void assertMoney(String expected,BigDecimal value) {
        assertNotNull(value);assertEquals(0,new BigDecimal(expected).compareTo(value));
    }
}
