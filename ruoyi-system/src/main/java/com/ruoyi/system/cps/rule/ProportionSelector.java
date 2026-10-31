package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Selects one already-matched proportion rule; version and matching are supplied by the rule store. */
public final class ProportionSelector {
    public enum Priority { TARGETED, NEWCOMER, TIERED, BASE }
    public record Candidate(long versionId, Priority priority, BigDecimal rate) {
        public Candidate {
            Objects.requireNonNull(priority, "priority");
            Objects.requireNonNull(rate, "rate");
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("rate must be in [0,1]");
        }
    }
    private ProportionSelector() { }
    public static Candidate select(List<Candidate> matches) {
        Objects.requireNonNull(matches, "matches");
        Candidate best = matches.stream().min(Comparator.comparing(Candidate::priority))
                .orElseThrow(() -> new IllegalArgumentException("no matching proportion rule"));
        if (matches.stream().filter(c -> c.priority() == best.priority()).count() != 1)
            throw new IllegalArgumentException("ambiguous matching proportion rules");
        return best;
    }
}
