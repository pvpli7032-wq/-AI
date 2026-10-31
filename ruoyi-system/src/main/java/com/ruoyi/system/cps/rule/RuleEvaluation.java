package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import com.ruoyi.union.adapter.Platform;
import static com.ruoyi.system.cps.rule.RulePolicy.*;
import static com.ruoyi.system.cps.rule.RuleDecision.*;

/** Pure matching/calculation. No database, user application, or current-version dependency. */
public final class RuleEvaluation {
    private RuleEvaluation() { }
    public static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }
    private static void nonNegative(BigDecimal value) {
        if (Objects.requireNonNull(value).signum() < 0) throw new IllegalArgumentException("negative value");
    }
    private static void rate(BigDecimal value) {
        nonNegative(value);
        if (value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("rate outside [0,1]");
    }
    public static void validate(RulePolicy p) {
        Objects.requireNonNull(p.platform()); rate(p.serviceRate()); rate(p.jdNonSelfRate());
        rate(p.payoutCostRate()); nonNegative(p.maximumCommission()); nonNegative(p.minimumRebate());
        Objects.requireNonNull(p.minimumMargin());
        if (p.maximumCommission().stripTrailingZeros().scale() > 2 || p.maximumCommission().signum() == 0)
            throw new IllegalArgumentException("maximum commission must be positive cents");
        if (p.platform() != Platform.JD && p.jdNonSelfRate().signum() != 0)
            throw new IllegalArgumentException("JD fee on non-JD platform");
        var keys = new HashSet<String>();
        int bases = 0;
        for (Proportion r : p.proportions()) {
            if (r.key() == null || r.key().isBlank() || !keys.add(r.key()))
                throw new IllegalArgumentException("duplicate/empty proportion key");
            Objects.requireNonNull(r.kind()); Objects.requireNonNull(r.mode()); Objects.requireNonNull(r.target());
            rate(r.rate());
            if (r.firstN() < 0 || r.dormantDays() < 0) throw new IllegalArgumentException("negative threshold");
            if (r.kind() == Kind.BASE) {
                bases++;
                if (!empty(r.target())) throw new IllegalArgumentException("base must be platform-wide");
            }
            if (r.kind() == Kind.TARGETED && empty(r.target())) throw new IllegalArgumentException("empty target");
            if ((r.kind() == Kind.NEWCOMER || r.kind() == Kind.DORMANCY) && r.firstN() < 1)
                throw new IllegalArgumentException("first N required");
            if (r.kind() == Kind.DORMANCY && (r.dormantDays() < 1 || r.publicActivity() == null || r.publicActivity().isBlank()))
                throw new IllegalArgumentException("dormancy requires visible public activity and days");
            if ((r.kind() == Kind.TIERED) != (r.mode() == Mode.CUMULATIVE_TIERS))
                throw new IllegalArgumentException("cumulative tiers belong exclusively to TIERED rules");
            if (r.mode() == Mode.FIXED && !r.tiers().isEmpty()) throw new IllegalArgumentException("fixed mode has tiers");
            if (r.mode() != Mode.FIXED && r.tiers().isEmpty()) throw new IllegalArgumentException("missing tiers");
            long lastCount = -1; BigDecimal lastAmount = BigDecimal.valueOf(-1);
            for (Tier t : r.tiers()) {
                nonNegative(t.amount()); rate(t.rate());
                if (t.count() < 0 || t.count() < lastCount || t.amount().compareTo(lastAmount) < 0
                        || (t.count() == lastCount && t.amount().compareTo(lastAmount) == 0))
                    throw new IllegalArgumentException("tiers must be strictly ordered AND thresholds");
                lastCount = t.count(); lastAmount = t.amount();
                if (r.mode() == Mode.INVITATION_TIERS && t.amount().signum() != 0)
                    throw new IllegalArgumentException("invitation tiers use count only");
                if (r.mode() == Mode.COMMISSION_TIERS && t.count() != 0)
                    throw new IllegalArgumentException("commission tiers use amount only");
            }
        }
        if (bases != 1) throw new IllegalArgumentException("exactly one platform base required");
        keys.clear();
        for (Incentive i : p.incentives()) {
            if (i.key() == null || i.key().isBlank() || !keys.add(i.key())) throw new IllegalArgumentException("incentive key");
            Objects.requireNonNull(i.kind()); nonNegative(i.fixed()); rate(i.rate());
            if (i.n() < 0 || i.withinDays() < 0 || (i.startsAt() != null && i.endsAt() != null && !i.endsAt().isAfter(i.startsAt())))
                throw new IllegalArgumentException("incentive threshold/period");
        }
    }
    private static boolean empty(Target t) {
        return t.users().isEmpty() && t.items().isEmpty() && t.shops().isEmpty() && t.categories().isEmpty();
    }
    private static boolean target(Target t, Facts f) {
        Evidence e = f.evidence();
        return (t.users().isEmpty() || t.users().contains(f.buyerUserId()))
            && (t.items().isEmpty() || f.itemId() != null && t.items().contains(f.itemId()))
            && (t.shops().isEmpty() || e.shopId() != null && t.shops().contains(e.shopId()))
            && (t.categories().isEmpty() || e.categoryId() != null && t.categories().contains(e.categoryId()));
    }
    private static void reliable(Evidence e) {
        if (e.sourceReference() == null || e.sourceReference().isBlank())
            throw new IllegalStateException("auditable paid-time qualification evidence unavailable");
    }
    private static long orders(Evidence e) {
        reliable(e);
        if (e.validOrders() == null || e.validOrders() < 0 || e.selfPurchase() == null)
            throw new IllegalStateException("valid-order/self-purchase evidence unavailable");
        return e.validOrders();
    }
    private static boolean matches(Proportion r, Facts f) {
        if (!target(r.target(), f)) return false;
        Evidence e = f.evidence();
        return switch (r.kind()) {
            case BASE, TARGETED -> true;
            case NEWCOMER -> orders(e) < r.firstN() && !e.selfPurchase();
            case TIERED -> {
                orders(e);
                if (e.validAmount() == null) throw new IllegalStateException("valid amount unavailable");
                nonNegative(e.validAmount());
                yield !e.selfPurchase() && r.tiers().stream().anyMatch(t -> e.validOrders() >= t.count()
                    && e.validAmount().compareTo(t.amount()) >= 0);
            }
            case DORMANCY -> {
                orders(e);
                if (e.lastValidOrderAt() == null || e.returnOrderOrdinal() == null)
                    throw new IllegalStateException("dormancy evidence unavailable");
                yield !e.selfPurchase() && e.returnOrderOrdinal() > 0 && e.returnOrderOrdinal() <= r.firstN()
                    && Duration.between(e.lastValidOrderAt(), f.paidAt()).toDays() >= r.dormantDays();
            }
        };
    }
    private static int priority(Kind kind) {
        return switch (kind) { case TARGETED -> 0; case NEWCOMER, DORMANCY -> 1; case TIERED -> 2; case BASE -> 3; };
    }
    private static BigDecimal selectedRate(Proportion r, Facts f) {
        BigDecimal result = r.rate();
        for (Tier t : r.tiers()) {
            boolean match;
            if (r.mode() == Mode.INVITATION_TIERS) {
                reliable(f.evidence());
                if (f.evidence().validInvitations() == null || f.evidence().validInvitations() < 0)
                    throw new IllegalStateException("qualified invitation evidence unavailable");
                match = f.evidence().validInvitations() >= t.count();
            } else if (r.mode() == Mode.CUMULATIVE_TIERS) {
                match = orders(f.evidence()) >= t.count() && f.evidence().validAmount().compareTo(t.amount()) >= 0;
            } else {
                if (t.count() != 0) throw new IllegalArgumentException("commission tier cannot require count");
                match = f.estimatedCommission().compareTo(t.amount()) >= 0;
            }
            if (match) result = t.rate();
        }
        return result;
    }
    public static RuleDecision choose(RulePolicy p, Facts f) {
        validate(p);
        if (f.buyerUserId() <= 0 || p.platform() != f.platform() || f.paidAt() == null
                || f.evidence() == null || !f.paidAt().equals(f.evidence().asOf()))
            throw new IllegalArgumentException("invalid paid-time facts");
        commission(p, f.estimatedCommission());
        // Only inspect lower priorities when required; missing evidence never fabricates a match.
        List<Proportion> candidates = new ArrayList<>();
        for (int pr = 0; pr <= 3 && candidates.isEmpty(); pr++) {
            for (Proportion r : p.proportions()) if (priority(r.kind()) == pr && matches(r, f)) candidates.add(r);
        }
        if (candidates.size() != 1) throw new IllegalArgumentException("ambiguous matching proportion rules");
        Proportion selected = candidates.getFirst();
        BigDecimal selectedRate = selectedRate(selected, f);
        if (selected.kind() == Kind.TARGETED && p.targetedWithTier()) {
            List<Proportion> tiers = p.proportions().stream().filter(r -> r.kind() == Kind.TIERED && matches(r, f)).toList();
            if (tiers.size() > 1) throw new IllegalArgumentException("ambiguous tier combination");
            if (!tiers.isEmpty()) {
                if (selected.mode() == Mode.INVITATION_TIERS || tiers.getFirst().mode() == Mode.INVITATION_TIERS)
                    throw new IllegalArgumentException("invitation mode is exclusive");
                selectedRate = selectedRate.max(selectedRate(tiers.getFirst(), f));
            }
        }
        if (!p.incentives().isEmpty()) throw new IllegalStateException("reward qualification/cost integration required");
        return new RuleDecision(selected.key(), selectedRate, List.of(),
            selected.kind() == Kind.DORMANCY ? List.of(selected.publicActivity()) : List.of());
    }
    private static void commission(RulePolicy p, BigDecimal commission) {
        nonNegative(commission);
        if (commission.stripTrailingZeros().scale() > 2 || commission.compareTo(p.maximumCommission()) > 0)
            throw new IllegalArgumentException("commission exceeds configured limit or is not cents");
    }
    public static Calculation calculate(Snapshot snapshot, BigDecimal actualCommission) {
        RulePolicy p = snapshot.policy(); commission(p, actualCommission);
        if (!p.incentives().isEmpty() || !snapshot.decision().incentiveKeys().isEmpty())
            throw new IllegalStateException("reward cost integration required");
        BigDecimal jd = BigDecimal.ZERO;
        if (p.platform() == Platform.JD && p.jdNonSelfRate().signum() != 0) {
            if (snapshot.evidence().jdSelfOperated() == null) throw new IllegalStateException("JD operation evidence unavailable");
            reliable(snapshot.evidence());
            if (!snapshot.evidence().jdSelfOperated()) jd = p.jdNonSelfRate();
        }
        return quote(p, actualCommission, snapshot.decision().userRate(), jd);
    }
    private static Calculation quote(RulePolicy p, BigDecimal commission, BigDecimal userRate, BigDecimal jd) {
        rate(userRate);
        var q = CommissionCalculator.calculate(commission, p.maximumCommission(), p.serviceRate(), jd,
            userRate, BigDecimal.ZERO, BigDecimal.ZERO, p.payoutCostRate());
        BigDecimal rebate = q.rebate().compareTo(new BigDecimal("0.01")) < 0 ? money(p.minimumRebate()) : q.rebate();
        BigDecimal cost = money(rebate.multiply(p.payoutCostRate()));
        return new Calculation(q.netCommission(), rebate, List.of(), cost, money(q.netCommission().subtract(rebate).subtract(cost)));
    }
    /** Exhaustive cent-domain proof (including zero). No client maxCost is accepted.
     * Worst possible rate is conservative even where targets/tiers cannot overlap.
     * Both JD fee paths are checked. Bound limits synchronous publishing work, not money precision. */
    public static BigDecimal worstMargin(RulePolicy p) {
        validate(p);
        if (!p.incentives().isEmpty()) throw new IllegalStateException("reward-stacked publication blocked until trusted reward costs integrated");
        long cents = p.maximumCommission().movePointRight(2).longValueExact();
        if (cents > 1_000_000) throw new IllegalArgumentException("publication proof domain exceeds 1000000 cents");
        BigDecimal highest = p.proportions().stream().map(Proportion::rate).max(Comparator.naturalOrder()).orElseThrow();
        for (Proportion r : p.proportions()) for (Tier t : r.tiers()) highest = highest.max(t.rate());
        BigDecimal worst = null;
        for (long c = 0; c <= cents; c++) {
            BigDecimal commission = BigDecimal.valueOf(c, 2);
            BigDecimal margin = quote(p, commission, highest, p.jdNonSelfRate()).margin()
                .min(quote(p, commission, highest, BigDecimal.ZERO).margin());
            worst = worst == null ? margin : worst.min(margin);
        }
        if (worst.compareTo(p.minimumMargin()) < 0) throw new IllegalArgumentException("rounded worst-case margin below minimum: " + worst);
        return worst;
    }
}
