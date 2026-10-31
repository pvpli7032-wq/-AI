package com.ruoyi.system.cps.settlement;

import com.ruoyi.system.cps.rule.RewardApi;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Rule/reward configuration adapter must supply actual payment-time settings, including empty configs.
 * An absent adapter is not an instruction to assume there are no rewards.
 */
public interface SettlementRewardTermsPort {
    RewardApi.Snapshot snapshot(long orderLineId, Long buyerUserId, UnionOrderLine line,
                               Long inviterUserId, boolean historicalUnknown);
}
