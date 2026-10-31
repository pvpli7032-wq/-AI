package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.domain.SysJob;
import com.ruoyi.quartz.domain.SysJobLog;
import com.ruoyi.quartz.service.ISysJobLogService;
import com.ruoyi.quartz.task.CpsAttributionTask;
import com.ruoyi.quartz.util.JobInvokeUtil;
import com.ruoyi.quartz.util.ScheduleUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Exercises the actual bean bridge and RuoYi's Integer-based invocation path. */
public class AttributionQuartzTaskTest {
    private AnnotationConfigApplicationContext context;
    private Object previousBeanFactory, previousApplicationContext;
    private OrderAttributionService attribution;
    private AttributionTokenService tokens;
    private PidPoolService pids;
    private Scheduler scheduler;
    private final BlockingQueue<SysJobLog> logs = new LinkedBlockingQueue<>();
    private final CountDownLatch releaseExpiry = new CountDownLatch(1);

    @Before public void setup() {
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        previousApplicationContext = ReflectionTestUtils.getField(SpringUtils.class, "applicationContext");
        attribution = mock(OrderAttributionService.class);
        tokens = mock(AttributionTokenService.class);
        pids = mock(PidPoolService.class);
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
    }

    @After public void cleanup() throws Exception {
        releaseExpiry.countDown();
        try {
            if (scheduler != null) scheduler.shutdown(true);
        } finally {
            try { if (context != null) context.close(); }
            finally {
                ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
                ReflectionTestUtils.setField(SpringUtils.class, "applicationContext", previousApplicationContext);
            }
        }
    }

    @Test public void realInvocationResolvesIntegerMethodsAndTheirNamedOperations() throws Exception {
        for (String target : List.of("cpsAttributionTask.expireUnclaimed(1)", "cpsAttributionTask.retrySettlement(500)",
                "cpsAttributionTask.cleanupTokens(200)", "cpsAttributionTask.cleanupReservations(200)")) {
            assertTrue(ScheduleUtils.whiteList(target));
            JobInvokeUtil.invokeMethod(job(1, target));
        }
        verify(attribution).expireUnclaimed(1);
        verify(attribution).retrySettlement(500);
        verify(tokens).cleanupExpired(200);
        verify(pids).cleanupExpiredConversionReservations(200);
        verifyNoMoreInteractions(attribution, tokens, pids);
    }

    @Test public void invalidBatchesAreRejectedBeforeAnyBusinessOperation() {
        CpsAttributionTask task = context.getBean(CpsAttributionTask.class);
        assertThrows(IllegalArgumentException.class, () -> task.expireUnclaimed(null));
        assertThrows(IllegalArgumentException.class, () -> task.retrySettlement(null));
        for (String method : List.of("expireUnclaimed", "retrySettlement", "cleanupTokens", "cleanupReservations")) {
            for (int invalid : new int[] {0, 501}) {
                SysJob job = job(1, "cpsAttributionTask." + method + "(" + invalid + ")");
                InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> JobInvokeUtil.invokeMethod(job));
                assertTrue(failure.getCause() instanceof IllegalArgumentException);
            }
        }
        verifyNoInteractions(attribution, tokens, pids);
    }

    @Test public void quartzSerializesTheSameTaskAndRunsTheOtherTaskWhenOneFails() throws Exception {
        AtomicInteger attempts = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger();
        CountDownLatch expiryStarted = new CountDownLatch(1);
        when(attribution.expireUnclaimed(200)).thenAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                if (attempts.incrementAndGet() == 1) {
                    expiryStarted.countDown();
                    if (!releaseExpiry.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
                    throw new IllegalStateException("synthetic expiry failure");
                }
                return 0;
            } finally { active.decrementAndGet(); }
        });
        Properties properties = new Properties();
        properties.setProperty("org.quartz.scheduler.instanceName", "line9-task-" + UUID.randomUUID());
        properties.setProperty("org.quartz.scheduler.makeSchedulerThreadDaemon", "true");
        properties.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        properties.setProperty("org.quartz.threadPool.threadCount", "2");
        properties.setProperty("org.quartz.threadPool.makeThreadsDaemons", "true");
        properties.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(properties).getScheduler();
        SysJob expiry = job(1, "cpsAttributionTask.expireUnclaimed(200)");
        SysJob retry = job(2, "cpsAttributionTask.retrySettlement(200)");
        ScheduleUtils.createScheduleJob(scheduler, expiry);
        ScheduleUtils.createScheduleJob(scheduler, retry);
        scheduler.start();

        scheduler.triggerJob(ScheduleUtils.getJobKey(expiry.getJobId(), expiry.getJobGroup()));
        assertTrue("expiry task should start", expiryStarted.await(10, TimeUnit.SECONDS));
        scheduler.triggerJob(ScheduleUtils.getJobKey(expiry.getJobId(), expiry.getJobGroup()));
        scheduler.triggerJob(ScheduleUtils.getJobKey(retry.getJobId(), retry.getJobGroup()));
        SysJobLog independent = nextLog();
        assertEquals(retry.getInvokeTarget(), independent.getInvokeTarget());
        assertEquals("0", independent.getStatus());
        assertEquals("duplicate expiry must wait while the first is running", 1, attempts.get());

        releaseExpiry.countDown();
        List<SysJobLog> expiryLogs = List.of(nextLog(), nextLog());
        assertTrue(expiryLogs.stream().allMatch(log -> expiry.getInvokeTarget().equals(log.getInvokeTarget())));
        assertEquals(1, expiryLogs.stream().filter(log -> "1".equals(log.getStatus())).count());
        assertEquals(1, expiryLogs.stream().filter(log -> "0".equals(log.getStatus())).count());
        assertFalse(expiryLogs.stream().filter(log -> "1".equals(log.getStatus())).findFirst().orElseThrow()
            .getExceptionInfo().isBlank());
        assertEquals(1, maximum.get());
        verify(attribution, times(2)).expireUnclaimed(200);
        verify(attribution).retrySettlement(200);
    }

    private SysJobLog nextLog() throws InterruptedException {
        SysJobLog log = logs.poll(10, TimeUnit.SECONDS);
        assertNotNull("Quartz task should complete and record its result", log);
        return log;
    }

    private static SysJob job(long id, String target) {
        SysJob job = new SysJob();
        job.setJobId(id);
        job.setJobName("synthetic attribution task " + id);
        job.setJobGroup("DEFAULT");
        job.setInvokeTarget(target);
        job.setCronExpression("0 0 0 1 1 ? 2099");
        job.setMisfirePolicy("3");
        job.setConcurrent("1");
        job.setStatus("0");
        return job;
    }
}
