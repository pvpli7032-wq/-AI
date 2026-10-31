package com.ruoyi.system.cps.attribution;

import static org.junit.Assert.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.Test;
import com.ruoyi.union.adapter.BoundedUnionExecutor;
import com.ruoyi.union.adapter.UnionCouponException;
import com.ruoyi.union.dtk.api.http.WebUtils;

public class BoundedUnionExecutorTest {
    @Test public void queueIsBoundedAndDoesNotRunRejectedWork() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try (BoundedUnionExecutor queue = new BoundedUnionExecutor(1, 1, Duration.ofSeconds(5))) {
            Future<String> first = callers.submit(() -> queue.execute(() -> {
                entered.countDown();
                await(release);
                return "first";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            // The calling thread is parked in Future.get only after the operation is queued.
            final Thread[] secondThread = new Thread[1];
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<String> second = callers.submit(() -> {
                secondThread[0] = Thread.currentThread();
                secondStarted.countDown();
                return queue.execute(() -> "second");
            });
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (secondThread[0].getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield();
            assertEquals(Thread.State.TIMED_WAITING, secondThread[0].getState());
            UnionCouponException busy = assertThrows(UnionCouponException.class,
                    () -> queue.execute(() -> { rejectedRan.set(true); return "rejected"; }));
            assertEquals("UPSTREAM_BUSY", busy.code());
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
            assertFalse(rejectedRan.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test public void totalTimeoutCancelsOperationAndCapacityRecovers() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        try (BoundedUnionExecutor queue = new BoundedUnionExecutor(1, 1, Duration.ofMillis(100))) {
            UnionCouponException timeout = assertThrows(UnionCouponException.class, () -> queue.execute(() -> {
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
                return "late";
            }));
            assertEquals("UPSTREAM_TIMEOUT", timeout.code());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals("recovered", queue.execute(() -> "recovered"));
        }
    }

    @Test public void increasingParallelismStartsQueuedWorkWithoutReplacingActiveWork() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        try (BoundedUnionExecutor queue = new BoundedUnionExecutor(1, 2, Duration.ofSeconds(5))) {
            Future<String> first = submitWaiting(callers, queue, () -> {
                firstEntered.countDown(); await(release); return "first";
            });
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            Future<String> second = submitWaiting(callers, queue, () -> {
                secondEntered.countDown(); return "second";
            });
            assertEquals(1, secondEntered.getCount());
            queue.reconfigure(2, 2, Duration.ofSeconds(5));
            assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
            assertFalse("Resizing must not cancel the active operation", first.isDone());
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test public void shrinkingParallelismAndQueuePreservesAndDrainsExistingBacklog() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(5);
        CountDownLatch active = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger drained = new AtomicInteger();
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try (BoundedUnionExecutor queue = new BoundedUnionExecutor(2, 3, Duration.ofSeconds(5))) {
            List<Future<String>> existing = new ArrayList<>();
            for (int i = 0; i < 2; i++) existing.add(submitWaiting(callers, queue, () -> {
                active.countDown(); await(release); return "active";
            }));
            assertTrue(active.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 3; i++) existing.add(submitWaiting(callers, queue, () -> {
                drained.incrementAndGet(); return "queued";
            }));
            queue.reconfigure(1, 1, Duration.ofSeconds(5));
            UnionCouponException busy = assertThrows(UnionCouponException.class,
                    () -> queue.execute(() -> { rejectedRan.set(true); return "rejected"; }));
            assertEquals("UPSTREAM_BUSY", busy.code());
            assertFalse(existing.get(0).isDone());
            assertFalse(existing.get(1).isDone());
            release.countDown();
            for (Future<String> task : existing) assertNotNull(task.get(2, TimeUnit.SECONDS));
            assertEquals(3, drained.get());
            assertFalse(rejectedRan.get());
            assertEquals("available", queue.execute(() -> "available"));
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test public void timeoutChangesApplyOnlyToNewlySubmittedWork() throws Exception {
        ExecutorService callers = Executors.newSingleThreadExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean timedOutWorkRan = new AtomicBoolean();
        try (BoundedUnionExecutor queue = new BoundedUnionExecutor(1, 1, Duration.ofSeconds(5))) {
            Future<String> old = submitWaiting(callers, queue, () -> {
                entered.countDown(); await(release); return "old deadline";
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queue.reconfigure(1, 1, Duration.ofMillis(100));
            UnionCouponException timeout = assertThrows(UnionCouponException.class,
                    () -> queue.execute(() -> { timedOutWorkRan.set(true); return "late"; }));
            assertEquals("UPSTREAM_TIMEOUT", timeout.code());
            assertFalse("New timeout must not shorten the already submitted operation", old.isDone());
            release.countDown();
            assertEquals("old deadline", old.get(2, TimeUnit.SECONDS));
            assertFalse(timedOutWorkRan.get());
            assertEquals("recovered", queue.execute(() -> "recovered"));
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test public void webUtilsUsesFiniteConnectionAndReadTimeouts() throws Exception {
        try {
            WebUtils.setTimeouts(1234, 5678);
            HttpURLConnection connection = WebUtils.getConnection(new URL("https://example.invalid/"), "GET", "application/json");
            assertEquals(1234, connection.getConnectTimeout());
            assertEquals(5678, connection.getReadTimeout());
            connection.disconnect();
            assertThrows(IllegalArgumentException.class, () -> WebUtils.setTimeouts(0, 100));
            assertThrows(IllegalArgumentException.class, () -> WebUtils.setTimeouts(100, 0));
        } finally {
            WebUtils.setTimeouts(3000, 10000);
        }
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
    }

    /** A caller parked in timed Future.get has already passed executor admission. */
    private static <T> Future<T> submitWaiting(ExecutorService callers, BoundedUnionExecutor queue,
            Supplier<T> operation) throws Exception {
        AtomicReference<Thread> caller = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        Future<T> task = callers.submit(() -> {
            caller.set(Thread.currentThread()); started.countDown();
            return queue.execute(operation);
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (caller.get().getState() != Thread.State.TIMED_WAITING && !task.isDone()
                && System.nanoTime() < deadline) Thread.yield();
        assertEquals("Operation must be admitted before reconfiguration", Thread.State.TIMED_WAITING, caller.get().getState());
        return task;
    }
}
