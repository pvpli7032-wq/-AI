package com.ruoyi.system.cps.settlement;

import java.time.Duration;
import java.time.Instant;
import org.junit.Test;
import static org.junit.Assert.*;

public class FreezeClockTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static Instant hour(long hours) { return START.plus(Duration.ofHours(hours)); }

    @Test public void countdownHasExactDeadlineAndNeverNegativeRemaining() {
        var clock = FreezeClock.start(START, Duration.ofHours(10));
        assertEquals(hour(10), clock.dueAt().orElseThrow());
        assertEquals(Duration.ofHours(10), clock.remainingAt(hour(-1)));
        assertEquals(Duration.ofHours(7), clock.remainingAt(hour(3)));
        assertFalse(clock.isMature(hour(10).minusNanos(1)));
        assertTrue(clock.isMature(hour(10)));
        assertEquals(Duration.ZERO, clock.remainingAt(hour(100)));
        assertEquals(clock, FreezeClock.start(START, Duration.ofHours(10)));
    }

    @Test public void pauseResumePreservesRemainingAndDuplicateCommandsAreIdempotent() {
        var original = FreezeClock.start(START, Duration.ofHours(10));
        var paused = original.pause(hour(3));
        assertTrue(paused.paused());
        assertEquals(Duration.ofHours(7), paused.remainingAt(hour(50)));
        assertFalse(paused.isMature(hour(50)));
        assertTrue(paused.dueAt().isEmpty());
        assertSame(paused, paused.pause(hour(4)));
        assertSame(paused, paused.pause(hour(3)));
        var resumed = paused.resume(hour(8));
        assertFalse(resumed.paused());
        assertEquals(hour(15), resumed.dueAt().orElseThrow());
        assertSame(resumed, resumed.resume(hour(9)));
        assertSame(resumed, resumed.resume(hour(8)));
        assertEquals(Duration.ofHours(6), resumed.remainingAt(hour(9)));
        var again = resumed.pause(hour(10)).resume(hour(20));
        assertEquals(hour(25), again.dueAt().orElseThrow());
        assertTrue(again.isMature(hour(25)));
    }

    @Test public void outOfOrderAndEqualTimestampCommandsDoNotDriftOrRewriteHistory() {
        var original = FreezeClock.start(START, Duration.ofHours(10));
        assertSame(original, original.pause(hour(-1)));
        // Equal timestamps: first accepted transition (including start) wins.
        assertSame(original, original.pause(START));
        assertSame(original, original.resume(hour(1)));
        var paused = original.pause(hour(3));
        assertSame(paused, paused.resume(hour(2)));
        assertSame(paused, paused.resume(hour(3)));
        var resumed = paused.resume(hour(8));
        assertSame(resumed, resumed.pause(hour(5)));
        assertSame(resumed, resumed.pause(hour(8)));
        assertEquals(Duration.ofHours(7), resumed.remainingAt(hour(1)));
        assertEquals(hour(15), resumed.dueAt().orElseThrow());
    }

    @Test public void expiredAndZeroDurationClocksCannotBeRefrozen() {
        var clock = FreezeClock.start(START, Duration.ofHours(10));
        var late = clock.pause(hour(11));
        assertFalse(late.paused());
        assertSame(clock, late);
        assertEquals(hour(10), late.dueAt().orElseThrow());
        assertEquals(Duration.ZERO, late.remainingAt(hour(11)));
        assertTrue(late.isMature(hour(11)));
        assertTrue(clock.pause(hour(10)).isMature(hour(10)));
        var disabled = FreezeClock.start(START, Duration.ZERO);
        assertTrue(disabled.isMature(START));
        assertFalse(disabled.pause(hour(1)).paused());
        assertThrows(IllegalArgumentException.class, () -> FreezeClock.start(START, Duration.ofNanos(-1)));
        assertThrows(NullPointerException.class, () -> FreezeClock.start(null, Duration.ZERO));
        assertThrows(NullPointerException.class, () -> FreezeClock.start(START, null));
        assertThrows(NullPointerException.class, () -> clock.pause(null));
        assertThrows(NullPointerException.class, () -> clock.resume(null));
        assertThrows(NullPointerException.class, () -> clock.remainingAt(null));
    }

    @Test public void nanosecondPrecisionIsRetainedAcrossPauseAndResume() {
        var clock = FreezeClock.start(START, Duration.ofNanos(10));
        var resumed = clock.pause(START.plusNanos(3)).resume(START.plusNanos(100));
        assertEquals(START.plusNanos(107), resumed.dueAt().orElseThrow());
        assertEquals(Duration.ofNanos(1), resumed.remainingAt(START.plusNanos(106)));
        assertTrue(resumed.isMature(START.plusNanos(107)));
    }
}
