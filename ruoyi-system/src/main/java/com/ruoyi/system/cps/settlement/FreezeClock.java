package com.ruoyi.system.cps.settlement;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Immutable freeze timer. Anchor is the last accepted transition, remaining is
 * measured at that anchor. Duplicate state commands and commands at/before the
 * anchor are ignored (first accepted transition wins at equal timestamps).
 * Earlier observations clamp to anchor, never move the timer backward.
 * Out-of-order events are NOT retroactively reconstructed; caller must audit them.
 * Persist the complete value atomically with the settlement state. No wall clock.
 */
public record FreezeClock(Duration remaining, Instant anchor, boolean paused) {
    public FreezeClock {
        Objects.requireNonNull(remaining, "remaining");
        Objects.requireNonNull(anchor, "anchor");
        if (remaining.isNegative()) { throw new IllegalArgumentException("negative remaining duration"); }
    }

    public static FreezeClock start(Instant receivedAt, Duration duration) {
        return new FreezeClock(duration, receivedAt, false);
    }

    public Duration remainingAt(Instant at) {
        Objects.requireNonNull(at, "at");
        if (paused || !at.isAfter(anchor)) { return remaining; }
        Duration elapsed = Duration.between(anchor, at);
        return elapsed.compareTo(remaining) >= 0 ? Duration.ZERO : remaining.minus(elapsed);
    }

    public FreezeClock pause(Instant at) {
        Objects.requireNonNull(at, "at");
        if (paused || !at.isAfter(anchor)) { return this; }
        Duration left = remainingAt(at);
        // Expired funds cannot be re-frozen by a late dispute command.
        return left.isZero() ? this : new FreezeClock(left, at, true);
    }

    public FreezeClock resume(Instant at) {
        Objects.requireNonNull(at, "at");
        if (!paused || !at.isAfter(anchor)) { return this; }
        return new FreezeClock(remaining, at, false);
    }

    public boolean isMature(Instant at) {
        return remainingAt(at).isZero() && !paused;
    }

    /** No deadline while paused; on resume the saved remaining time starts again. */
    public Optional<Instant> dueAt() {
        return paused ? Optional.empty() : Optional.of(anchor.plus(remaining));
    }
}
