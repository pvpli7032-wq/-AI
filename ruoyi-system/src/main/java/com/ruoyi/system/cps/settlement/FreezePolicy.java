package com.ruoyi.system.cps.settlement;

import com.ruoyi.union.adapter.Platform;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Configured per-platform lower-inclusive amount tiers; no production rates/defaults.
 * Amount basis (e.g. rebate snapshot) must be chosen consistently by the caller.
 * Paid-to-received <=24h uses the configured fast duration instead of the tier.
 * User-disabled freeze overrides both to zero; it does not suppress suspicion.
 * Paid-to-settled <24h only flags suspicion, never invents a risk action.
 * Missing paid/settled times do not fabricate timestamps or infer suspicion.
 */
public final class FreezePolicy {
    private static final Duration ONE_DAY = Duration.ofHours(24);
    private final Map<Platform, List<Tier>> tiers;
    private final Map<Platform, Duration> fastDurations;

    public record Tier(BigDecimal minimumAmount, Duration duration) {
        public Tier {
            requireAmount(minimumAmount);
            requireDuration(duration);
        }
    }

    public record Decision(Duration duration, boolean fastReceipt, boolean suspiciousSettlement) { }

    /** Each platform supplied must have a zero-based, strictly ascending tier list
     * and an explicit fast duration. Unconfigured platforms fail closed.
     */
    public FreezePolicy(Map<Platform, List<Tier>> tiers, Map<Platform, Duration> fastDurations) {
        Objects.requireNonNull(tiers, "tiers");
        Objects.requireNonNull(fastDurations, "fastDurations");
        EnumMap<Platform, List<Tier>> copy = new EnumMap<>(Platform.class);
        for (var entry : tiers.entrySet()) {
            List<Tier> list = List.copyOf(entry.getValue());
            if (list.isEmpty() || list.get(0).minimumAmount().signum() != 0) {
                throw new IllegalArgumentException("tiers must start at zero");
            }
            for (int i = 1; i < list.size(); i++) {
                if (list.get(i - 1).minimumAmount().compareTo(list.get(i).minimumAmount()) >= 0) {
                    throw new IllegalArgumentException("tiers must be strictly ascending");
                }
            }
            copy.put(Objects.requireNonNull(entry.getKey()), list);
        }
        if (!copy.keySet().equals(fastDurations.keySet())) {
            throw new IllegalArgumentException("tier and fast-duration platforms differ");
        }
        fastDurations.values().forEach(FreezePolicy::requireDuration);
        this.tiers = Map.copyOf(copy);
        this.fastDurations = Map.copyOf(fastDurations);
    }

    public Decision evaluate(Platform platform, BigDecimal amount, Instant paidAt,
            Instant receivedAt, Instant settledAt, boolean userDisabledFreeze) {
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(receivedAt, "receivedAt");
        requireAmount(amount);
        List<Tier> configured = tiers.get(platform);
        if (configured == null) { throw new IllegalArgumentException("platform freeze policy missing"); }
        Duration toReceipt = interval(paidAt, receivedAt);
        Duration toSettlement = interval(paidAt, settledAt);
        if (settledAt != null && settledAt.isBefore(receivedAt)) {
            throw new IllegalArgumentException("settlement precedes receipt");
        }
        boolean fast = toReceipt != null && toReceipt.compareTo(ONE_DAY) <= 0;
        boolean suspicious = toSettlement != null && toSettlement.compareTo(ONE_DAY) < 0;
        Duration duration = configured.get(0).duration();
        for (Tier tier : configured) {
            if (amount.compareTo(tier.minimumAmount()) < 0) { break; }
            duration = tier.duration();
        }
        return new Decision(userDisabledFreeze ? Duration.ZERO
                : fast ? fastDurations.get(platform) : duration, fast, suspicious);
    }

    /** Demonstration-only defaults: deliberately synthetic, not launch configuration.
     * The legacy commission.freeze.days setting must be explicitly mapped upstream;
     * this pure policy does not read or silently replace it.
     */
    public static FreezePolicy syntheticDefaults() {
        EnumMap<Platform, List<Tier>> tiers = new EnumMap<>(Platform.class);
        EnumMap<Platform, Duration> fast = new EnumMap<>(Platform.class);
        for (Platform platform : Platform.values()) {
            tiers.put(platform, List.of(new Tier(BigDecimal.ZERO, Duration.ofDays(2)),
                    new Tier(new BigDecimal("100.00"), Duration.ofDays(3))));
            fast.put(platform, Duration.ofDays(1));
        }
        return new FreezePolicy(tiers, fast);
    }

    private static Duration interval(Instant start, Instant end) {
        if (start == null || end == null) { return null; }
        if (end.isBefore(start)) { throw new IllegalArgumentException("negative order time interval"); }
        return Duration.between(start, end);
    }

    private static void requireAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) { throw new IllegalArgumentException("negative amount"); }
    }

    private static void requireDuration(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) { throw new IllegalArgumentException("negative freeze duration"); }
    }
}
