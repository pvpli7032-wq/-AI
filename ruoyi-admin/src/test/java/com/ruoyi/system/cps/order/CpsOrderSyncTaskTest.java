package com.ruoyi.system.cps.order;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.quartz.task.CpsOrderSyncTask;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class CpsOrderSyncTaskTest
{
    private DefaultListableBeanFactory beans;
    private Object previousBeanFactory;
    private final CpsOrderSyncTask task = new CpsOrderSyncTask();

    @Before
    public void setup()
    {
        beans = new DefaultListableBeanFactory();
        previousBeanFactory = ReflectionTestUtils.getField(SpringUtils.class, "beanFactory");
        new SpringUtils().postProcessBeanFactory(beans);
    }

    @After
    public void teardown()
    {
        ReflectionTestUtils.setField(SpringUtils.class, "beanFactory", previousBeanFactory);
    }

    public static class Jobs
    {
        int incremental;
        int backfill;
        int afterSale;
        public void incremental() { incremental++; }
        public void backfill() { backfill++; }
        public void afterSale() { afterSale++; }
    }

    public static class FailingJobs
    {
        final IllegalStateException failure = new IllegalStateException("synthetic failure");
        public void incremental() { throw failure; }
    }

    @Test
    public void eachEntryPointDispatchesToItsOwnCollectionJob()
    {
        Jobs jobs = new Jobs();
        beans.registerSingleton("orderCollectionJobs", jobs);
        task.incremental();
        task.backfill();
        task.afterSale();
        assertEquals(1, jobs.incremental);
        assertEquals(1, jobs.backfill);
        assertEquals(1, jobs.afterSale);
    }

    @Test
    public void missingBeanAndMissingMethodAreReported()
    {
        assertThrows(NoSuchBeanDefinitionException.class, task::incremental);
        beans.registerSingleton("orderCollectionJobs", new Object());
        assertThrows(IllegalStateException.class, task::incremental);
    }

    @Test
    public void businessFailureReachesQuartz()
    {
        FailingJobs jobs = new FailingJobs();
        beans.registerSingleton("orderCollectionJobs", jobs);
        assertSame(jobs.failure, assertThrows(IllegalStateException.class, task::incremental));
    }
}
