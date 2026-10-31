package com.ruoyi.quartz.task;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.springframework.stereotype.Component;
import com.ruoyi.common.utils.spring.SpringUtils;

/** Quartz entry points; windows, durable failure handling and retry belong to the collection service. */
@Component("cpsOrderSyncTask")
public class CpsOrderSyncTask
{
    public void incremental() { invoke("incremental"); }
    public void backfill() { invoke("backfill"); }
    public void afterSale() { invoke("afterSale"); }

    private void invoke(String name)
    {
        Object service = SpringUtils.getBean("orderCollectionJobs");
        try
        {
            Method method = service.getClass().getMethod(name);
            method.invoke(service);
        }
        catch (InvocationTargetException e)
        {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("订单采集任务失败: " + name, cause);
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("订单采集任务不可调用: " + name, e);
        }
    }
}
