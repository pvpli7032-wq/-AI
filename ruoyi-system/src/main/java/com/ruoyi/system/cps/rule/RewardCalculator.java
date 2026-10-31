package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.util.*;
import static com.ruoyi.system.cps.rule.RewardApi.*;

/** Pure policy decisions; no account or live rule dependencies. */
public final class RewardCalculator {
    private RewardCalculator() {}
    public static boolean valid(Snapshot s, BigDecimal paid, BigDecimal commission) {
        return paid.compareTo(s.minimumPaid()) > 0 && commission.compareTo(s.minimumCommission()) > 0;
    }
    public static List<Long> lockUsers(Snapshot s) {
        return s.inviterId() == null ? List.of(s.buyerId()) :
            new TreeSet<>(List.of(s.buyerId(), s.inviterId())).stream().toList();
    }
    /** Ordinal counts all lifetime qualified orders, including later-invalidated ones. No slot recycling. */
    public static List<Award> awards(Snapshot s, Qualification q, long ordinal, long validCount, BigDecimal priorRefund) {
        List<Award> result = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Config c : s.configs()) {
            if (!c.applies(s)) continue;
            boolean invitation = switch (c.type()) {
                case INVITATION_RATIO, INVITEE_FIRST_FIXED, INVITEE_FIRST_N_FIXED -> true;
                default -> false;
            };
            if (invitation && s.inviterId() == null) continue;
            boolean eligible = switch (c.type()) {
                case INVITATION_RATIO -> true;
                case INVITEE_FIRST_FIXED -> ordinal == 1;
                case INVITEE_FIRST_N_FIXED, FIRST_N_SUBSIDY -> ordinal <= c.n();
                case OWN_QUANTITY -> validCount >= c.n();
            };
            if (!eligible) continue;
            BigDecimal amount = money(c.mode() == Mode.RATIO ? q.userRebate().multiply(c.value()) :
                c.value().multiply(BigDecimal.ONE.subtract(priorRefund)));
            if (amount.signum() == 0) continue;
            // Version and inviter deliberately excluded: changing either never resets a lifetime entitlement.
            String id = c.type() + ":" + s.buyerId() + ":" + switch (c.type()) {
                case OWN_QUANTITY, INVITEE_FIRST_FIXED -> "once";
                default -> Long.toString(s.orderId());
            };
            result.add(new Award(id, invitation ? s.inviterId() : s.buyerId(), s.orderId(), s.buyerId(),
                c.type(), c.type() == Type.OWN_QUANTITY ? c.n() : 0, amount, money(BigDecimal.ZERO)));
            total = total.add(amount);
        }
        if (total.compareTo(s.maximumReward()) > 0) throw new IllegalArgumentException("snapshot reward budget exceeded");
        return List.copyOf(result);
    }
    public static BigDecimal recoveryTarget(Award a, BigDecimal ratio) {
        return money(a.amount().multiply(RewardApi.ratio(ratio))).min(a.amount());
    }
    /** Divide only at the final cent: pre-rounding a ratio can misstate large recoveries. */
    public static BigDecimal recoveryTarget(Award a, BigDecimal cumulative, BigDecimal baseline) {
        RewardApi.ratio(cumulative); RewardApi.ratio(baseline);
        if (baseline.compareTo(BigDecimal.ONE) == 0) return a.amount();
        return a.amount().multiply(cumulative.subtract(baseline).max(BigDecimal.ZERO))
            .divide(BigDecimal.ONE.subtract(baseline), 2, java.math.RoundingMode.HALF_UP).min(a.amount());
    }
}
