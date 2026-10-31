package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shares the mandatory settlement transaction and reward-user locks. Never writes account balances. */
@Repository
public class RewardRepricingJdbcStore implements RewardRepricingStore {
    private final JdbcTemplate jdbc;
    public RewardRepricingJdbcStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Basis basis(String id) {
        return jdbc.query("SELECT award_id,entitlement,refund_recovered,revision_key FROM cps_reward_entitlement WHERE award_id=? FOR UPDATE",
            (r, n) -> new Basis(r.getString(1), r.getBigDecimal(2), r.getBigDecimal(3), r.getString(4)), id)
            .stream().findFirst().orElse(null);
    }
    public void revise(Basis previous, Basis next, BigDecimal originalGrant, String evidence) {
        jdbc.update("INSERT INTO cps_reward_basis_revision(revision_key,award_id,previous_key,original_grant,previous_entitlement,entitlement,evidence) VALUES(?,?,?,?,?,?,?)",
            next.revisionKey(), next.awardId(), previous == null ? null : previous.revisionKey(), originalGrant,
            previous == null ? originalGrant : previous.entitlement(), next.entitlement(), evidence);
        if (previous == null) {
            jdbc.update("INSERT INTO cps_reward_entitlement(award_id,entitlement,refund_recovered,revision_key) VALUES(?,?,?,?)",
                next.awardId(), next.entitlement(), next.refundRecovered(), next.revisionKey());
        } else if (jdbc.update("UPDATE cps_reward_entitlement SET entitlement=?,revision_key=? WHERE award_id=? AND revision_key=?",
                next.entitlement(), next.revisionKey(), next.awardId(), previous.revisionKey()) != 1) {
            throw new IllegalStateException("reward basis revision conflict");
        }
    }
    public void recover(String id, BigDecimal cumulative) {
        if (jdbc.update("UPDATE cps_reward_entitlement SET refund_recovered=? WHERE award_id=? AND refund_recovered<=? AND entitlement>=?",
                cumulative, id, cumulative, cumulative) != 1) throw new IllegalStateException("reward basis recovery conflict");
    }
}
