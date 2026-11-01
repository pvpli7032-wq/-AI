package com.ruoyi.union.adapter;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** One instance per platform bounds in-flight calls, waiting calls and total wait time. */
public final class BoundedUnionExecutor implements AutoCloseable {
    private final ThreadPoolExecutor executor;
    private final AdmissionQueue queue;
    private final Object configurationLock = new Object();
    private long timeoutMillis;
    private int configuredParallelism;
    private int configuredQueueCapacity;

    public BoundedUnionExecutor(int parallelism, int queueCapacity, Duration timeout) {
        validate(parallelism, queueCapacity, timeout);
        timeoutMillis = timeout.toMillis();
        configuredParallelism = parallelism;
        configuredQueueCapacity = queueCapacity;
        queue = new AdmissionQueue(queueCapacity);
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(parallelism, parallelism, 0L, TimeUnit.MILLISECONDS,
                queue, task -> {
                    Thread thread = new Thread(task, "coupon-alliance-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private static void validate(int parallelism, int queueCapacity, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (parallelism < 1 || queueCapacity < 1 || timeout.isNegative() || timeout.isZero()
                || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("Alliance concurrency, queue and timeout must be positive");
        }
    }

    /**
     * Update admission limits in the existing pool. A decrease drains existing running
     * and queued work naturally; it never cancels them or creates a second overlapping pool.
     * The timeout is snapshotted on submission and does not shorten an existing request.
     */
    public void reconfigure(int parallelism, int queueCapacity, Duration timeout) {
        validate(parallelism, queueCapacity, timeout);
        synchronized (configurationLock) {
            if (executor.isShutdown()) throw new IllegalStateException("Alliance executor is closed");
            if (configuredQueueCapacity != queueCapacity) {
                queue.setCapacity(queueCapacity);
                configuredQueueCapacity = queueCapacity;
            }
            if (configuredParallelism != parallelism) {
                if (parallelism > executor.getMaximumPoolSize()) {
                    executor.setMaximumPoolSize(parallelism);
                    executor.setCorePoolSize(parallelism);
                } else {
                    executor.setCorePoolSize(parallelism);
                    executor.setMaximumPoolSize(parallelism);
                }
                configuredParallelism = parallelism;
            }
            timeoutMillis = timeout.toMillis();
        }
    }

    public <T> T execute(Supplier<T> operation) {
        Future<T> future;
        long submissionTimeout;
        try {
            synchronized (configurationLock) {
                submissionTimeout = timeoutMillis;
                future = executor.submit(Objects.requireNonNull(operation, "operation")::get);
            }
        } catch (RejectedExecutionException busy) {
            throw new UnionCouponException("UPSTREAM_BUSY", "联盟服务繁忙，请稍后重试");
        }
        try {
            return future.get(submissionTimeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            cancel(future);
            throw new UnionCouponException("UPSTREAM_TIMEOUT", "联盟服务响应超时，请稍后重试");
        } catch (InterruptedException interrupted) {
            cancel(future);
            Thread.currentThread().interrupt();
            throw new UnionCouponException("UPSTREAM_INTERRUPTED", "联盟请求已中断");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof UnionCouponException couponFailure) throw couponFailure;
            throw new UnionCouponException("UPSTREAM_FAILURE", "联盟服务暂不可用", failed.getCause());
        }
    }

    private void cancel(Future<?> future) {
        future.cancel(true);
        // A timed-out queued request must not consume a slot until earlier requests finish.
        if (future instanceof Runnable task) executor.remove(task);
    }

    @Override
    public void close() {
        synchronized (configurationLock) {
            executor.shutdownNow();
        }
    }

    /** TPE inserts exclusively through offer; capacity changes never discard queued work. */
    private static final class AdmissionQueue extends LinkedBlockingQueue<Runnable> {
        private int capacity;
        AdmissionQueue(int capacity) { this.capacity = capacity; }
        synchronized void setCapacity(int capacity) { this.capacity = capacity; }
        @Override public synchronized boolean offer(Runnable task) {
            return size() < capacity && super.offer(task);
        }
        @Override public synchronized int remainingCapacity() {
            return Math.max(0, capacity - size());
        }
    }
}
