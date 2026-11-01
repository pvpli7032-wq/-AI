package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.util.List;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Rule/reward owner supplies this adapter. There are deliberately no production defaults. */
public interface SettlementTermsPort {
    Terms snapshot(long orderLineId, Long buyerUserId, UnionOrderLine line, Long inviterUserId);
    Terms recalculate(Terms snapshot, UnionOrderLine line);

    record Benefit(String type, Long beneficiaryUserId, BigDecimal amount, Long ruleVersionId) {
        public Benefit {
            if (!List.of("REBATE", "REFERRAL", "SUBSIDY").contains(type)
                    || beneficiaryUserId == null || beneficiaryUserId <= 0 || ruleVersionId == null
                    || ruleVersionId <= 0 || amount == null || amount.signum() < 0)
                throw new IllegalArgumentException("Invalid settlement benefit");
            amount = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        }
    }
    record Terms(List<Benefit> benefits, BigDecimal maxCommission, String freezePolicy, BigDecimal platformAmount, String ruleSnapshotJson) {
        public Terms {
            benefits = List.copyOf(benefits);
            if (maxCommission == null || maxCommission.signum() < 0 || freezePolicy == null || freezePolicy.isBlank()
                    || platformAmount == null || ruleSnapshotJson == null || ruleSnapshotJson.isBlank())
                throw new IllegalArgumentException("Missing settlement ceiling/freeze policy");
            long unique = benefits.stream().map(b -> b.type() + ":" + b.beneficiaryUserId()).distinct().count();
            if (unique != benefits.size()) throw new IllegalArgumentException("Duplicate settlement benefit");
        }
    }
}
