package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.contract.RebateStatus;

/** Only order, item and event persistence. Account mutations are exclusively LedgerService's job. */
@Repository
public class SettlementRepository {
    final JdbcTemplate jdbc;
    public SettlementRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public static class Position {
        public long id;
        public Long buyer;
        public OrderStatus status, previous;
        public BigDecimal commission, reportedCommission, recovered = BigDecimal.ZERO;
        public Instant lastEventAt, rewardRetryAt;
        public boolean rewardPending, manualHold;
        public SettlementTermsPort.Terms terms, actualTerms;
        public SettlementTermsPort.Terms effectiveTerms() { return actualTerms == null ? terms : actualTerms; }
        public boolean referralManual;
        public String evidence;
    }
    public static class Item {
        public long id, user;
        public String type, phase;
        public Long version;
        public BigDecimal original, recovered;
        public BigDecimal adjustment = BigDecimal.ZERO;
        public boolean newlyFrozen;
        public RebateStatus status;
        public Instant due, paused;
        public FreezeClock clock;
    }
    Map<String,Object> lockOrder(long id) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM sys_order_commission WHERE id=? FOR UPDATE", id);
        if (rows.size() != 1) throw new IllegalStateException("Settlement order line does not exist");
        return rows.getFirst();
    }
    Position load(long id) {
        List<Position> rows = jdbc.query("SELECT * FROM cps_settlement_position WHERE order_line_id=? FOR UPDATE", (r,n) -> {
            Position p = new Position(); p.id = id; p.buyer = r.getObject("buyer_user_id", Long.class);
            p.status = value(OrderStatus.class, r.getString("order_status"));
            p.previous = value(OrderStatus.class, r.getString("previous_status"));
            p.commission = r.getBigDecimal("original_commission"); p.recovered = r.getBigDecimal("recovered_commission");
            p.terms = JSON.parseObject(r.getString("terms_json"), SettlementTermsPort.Terms.class);
            p.actualTerms = JSON.parseObject(r.getString("actual_terms_json"), SettlementTermsPort.Terms.class);
            p.reportedCommission = r.getBigDecimal("reported_commission"); p.lastEventAt = instant(r.getTimestamp("last_event_at"));
            p.manualHold = r.getBoolean("manual_hold");
            p.rewardPending = r.getBoolean("reward_pending"); p.rewardRetryAt = instant(r.getTimestamp("reward_retry_at"));
            p.referralManual = r.getBoolean("referral_manual"); p.evidence = r.getString("invitation_evidence"); return p;
        }, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    void save(Position p) {
        jdbc.update("INSERT INTO cps_settlement_position(order_line_id,buyer_user_id,order_status,previous_status,original_commission,"
            + "recovered_commission,terms_json,referral_manual,invitation_evidence,actual_terms_json,reported_commission,last_event_at,reward_pending,reward_retry_at,manual_hold) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
            + "buyer_user_id=VALUES(buyer_user_id),order_status=VALUES(order_status),previous_status=VALUES(previous_status),"
            + "original_commission=VALUES(original_commission),recovered_commission=VALUES(recovered_commission),terms_json=VALUES(terms_json),"
            + "referral_manual=VALUES(referral_manual),invitation_evidence=VALUES(invitation_evidence),actual_terms_json=VALUES(actual_terms_json),"
            + "reported_commission=VALUES(reported_commission),last_event_at=VALUES(last_event_at),"
            + "reward_pending=VALUES(reward_pending),reward_retry_at=VALUES(reward_retry_at),manual_hold=VALUES(manual_hold)", p.id,p.buyer,
            p.status == null ? null : p.status.name(),p.previous == null ? null : p.previous.name(),p.commission,p.recovered,
            p.terms == null ? null : JSON.toJSONString(p.terms),p.referralManual,p.evidence,
            p.actualTerms == null ? null : JSON.toJSONString(p.actualTerms),p.reportedCommission,time(p.lastEventAt),
            p.rewardPending,time(p.rewardRetryAt),p.manualHold);
    }
    String receipt(String key) {
        List<String> rows = jdbc.queryForList("SELECT fingerprint FROM cps_settlement_receipt WHERE event_key=?", String.class,key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    void receipt(String key, long id, String fingerprint) {
        jdbc.update("INSERT INTO cps_settlement_receipt(event_key,order_line_id,fingerprint) VALUES(?,?,?)",key,id,fingerprint);
    }
    void manual(long id, String key, String reason) {
        jdbc.update("INSERT INTO cps_settlement_manual(order_line_id,event_key,reason) VALUES(?,?,?) ON DUPLICATE KEY UPDATE reason=VALUES(reason)",id,key,reason);
    }
    void holdEvent(long id,String key,String reason,String payload) {
        jdbc.update("INSERT INTO cps_settlement_hold_event(event_key,order_line_id,reason,event_json) VALUES(?,?,?,?)",
            key,id,reason,payload);
    }
    /**
     * A permission-checked import may prove a risk without enough evidence for an OrderEvent.
     * The processing observation below is only a safety-clock anchor, never alliance state time.
     * The caller's line transaction owns the hold and its MANUAL_REVIEW projection together.
     */
    void holdUnprocessableEvidence(long id,String sourceKey,String reason,String rawEvidenceJson) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Manual evidence hold requires the import transaction");
        if (id<=0 || sourceKey==null || sourceKey.isBlank() || sourceKey.length()>128
                || reason==null || reason.isBlank() || reason.length()>255
                || rawEvidenceJson==null || rawEvidenceJson.isBlank())
            throw new IllegalArgumentException("Incomplete manual hold evidence");
        lockOrder(id);
        // Keep evidence-only identities separate from real OrderEvent receipt identities.
        String key="manual-evidence:"+DefaultSettlementService.digest(sourceKey);
        List<Map<String,Object>> prior=jdbc.queryForList(
            "SELECT order_line_id,reason,JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.sourceKey')) AS source_key,"
            + "JSON_UNQUOTE(JSON_EXTRACT(event_json,'$.sourceEvidence')) AS source_evidence "
            + "FROM cps_settlement_hold_event WHERE event_key=? FOR UPDATE",key);
        if (!prior.isEmpty()) {
            Map<String,Object> stored=prior.getFirst();
            if (((Number)stored.get("order_line_id")).longValue()!=id
                    || !sourceKey.equals(stored.get("source_key")) || !reason.equals(stored.get("reason"))
                    || !rawEvidenceJson.equals(stored.get("source_evidence")))
                throw new IllegalArgumentException("Manual hold key payload mismatch");
            return;
        }
        Position p=load(id);
        if (p==null) { p=new Position(); p.id=id; }
        Instant observedAt=Instant.now();
        for (Item item:items(id)) {
            if (!"FROZEN".equals(item.phase) || item.paused!=null
                    || item.status==RebateStatus.VOID || item.status==RebateStatus.FULLY_RECOVERED) continue;
            Instant heldAt=item.clock!=null && observedAt.isBefore(item.clock.anchor())
                ? item.clock.anchor() : observedAt;
            item.paused=heldAt;
            if (item.clock!=null)
                item.clock=new FreezeClock(item.clock.remainingAt(heldAt),heldAt,true);
            item.status=RebateStatus.PAUSED; save(item);
        }
        p.manualHold=true; save(p);
        Map<String,Object> evidence=new java.util.LinkedHashMap<>();
        evidence.put("kind","UNPROCESSABLE_PENALTY_EVIDENCE");
        evidence.put("sourceKey",sourceKey); evidence.put("sourceEvidence",rawEvidenceJson);
        evidence.put("observedAt",observedAt);
        holdEvent(id,key,reason,JSON.toJSONString(evidence));
        manual(id,sourceKey,reason);
    }
    boolean legacy(long id, Map<String,Object> order) {
        String status = String.valueOf(order.get("order_status"));
        if (List.of("settled", "confirmed", "refunded").contains(status)
                || "1".equals(String.valueOf(order.get("settle_status")))) return true;
        Integer flows = jdbc.queryForObject("SELECT COUNT(*) FROM sys_commission_flow WHERE order_commission_id=? OR related_order_id=?",Integer.class,id,String.valueOf(id));
        if (flows != null && flows > 0) return true;
        Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM cps_settle_item WHERE order_line_id=?",Integer.class,id);
        return items != null && items > 0;
    }
    void insert(long line, SettlementTermsPort.Benefit b) {
        jdbc.update("INSERT INTO cps_settle_item(order_line_id,user_id,type,rule_version_id,est_amount,final_amount,status,fund_phase,recovered_amount) "
            + "VALUES(?,?,?,?,?,?,'ESTIMATED','NONE',0)",line,b.beneficiaryUserId(),b.type(),b.ruleVersionId(),b.amount(),b.amount());
    }
    List<Item> items(long id) {
        return jdbc.query("SELECT * FROM cps_settle_item WHERE order_line_id=? ORDER BY user_id,id FOR UPDATE", (r,n) -> {
            Item i = new Item(); i.id=r.getLong("id"); i.user=r.getLong("user_id"); i.type=r.getString("type");
            i.version=r.getObject("rule_version_id",Long.class); i.original=r.getBigDecimal("final_amount");
            i.recovered=r.getBigDecimal("recovered_amount"); i.status=RebateStatus.valueOf(r.getString("status"));
            i.phase=r.getString("fund_phase"); i.due=instant(r.getTimestamp("unfreeze_at")); i.paused=instant(r.getTimestamp("paused_at"));
            Instant anchor=instant(r.getTimestamp("freeze_anchor"));
            if (anchor!=null) i.clock=new FreezeClock(java.time.Duration.ofMillis(r.getLong("freeze_remaining_millis")),anchor,r.getBoolean("freeze_paused"));
            return i;
        },id);
    }
    void save(Item i) {
        jdbc.update("UPDATE cps_settle_item SET final_amount=?,recovered_amount=?,status=?,fund_phase=?,unfreeze_at=?,paused_at=?,freeze_anchor=?,freeze_remaining_millis=?,freeze_paused=? WHERE id=?",
            i.original,i.recovered,i.status.name(),i.phase,time(i.due),time(i.paused),
            i.clock==null ? null : time(i.clock.anchor()),i.clock==null ? null : i.clock.remaining().toMillis(),i.clock!=null && i.clock.paused(),i.id);
    }
    void order(Position p) {
        if (p.buyer == null || p.terms == null) {
            jdbc.update("UPDATE sys_order_commission SET line_status=? WHERE id=?",p.status.name(),p.id); return;
        }
        SettlementTermsPort.Terms effective = p.effectiveTerms();
        BigDecimal self = sum(effective,"REBATE"), referral = sum(effective,"REFERRAL");
        jdbc.update("UPDATE sys_order_commission SET line_status=?,agent_user_id=?,total_commission=?,platform_amount=?,self_commission_amount=?,promotion_commission_amount=? WHERE id=?",
            p.status.name(),p.buyer,p.commission == null ? p.reportedCommission : p.commission,effective.platformAmount(),self,referral,p.id);
    }
    private BigDecimal sum(SettlementTermsPort.Terms t,String type) {
        return t.benefits().stream().filter(b -> b.type().equals(type)).map(SettlementTermsPort.Benefit::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    BigDecimal paidAmount(long id) {
        return jdbc.queryForObject("SELECT order_amount FROM sys_order_commission WHERE id=?",BigDecimal.class,id);
    }
    List<Long> due(Instant now, int limit) {
        // Discovery takes no locks. Each candidate subsequently locks its order, cursor, items,
        // reward users, then ledger accounts, just like event processing. Deferred rewards
        // rotate behind other due work rather than starving the batch's first page.
        return jdbc.queryForList("SELECT order_line_id FROM ("
            + "SELECT order_line_id,unfreeze_at AS ready_at FROM cps_settle_item WHERE fund_phase='FROZEN' "
            + "AND paused_at IS NULL AND unfreeze_at<=? AND status IN ('FROZEN','PARTIALLY_RECOVERED') "
            + "UNION ALL SELECT p.order_line_id,p.reward_retry_at AS ready_at FROM cps_settlement_position p "
            + "JOIN cps_reward_order_state r ON r.order_id=p.order_line_id "
            + "WHERE p.reward_pending=1 AND p.reward_retry_at<=? AND r.qualified=0 AND r.closed=0 "
            + "AND p.order_status<>'DISPUTED') work WHERE NOT EXISTS "
            + "(SELECT 1 FROM cps_settlement_position held WHERE held.order_line_id=work.order_line_id AND held.manual_hold=1) "
            + "GROUP BY order_line_id "
            + "ORDER BY MIN(ready_at),order_line_id LIMIT ?",Long.class,time(now),time(now),limit);
    }
    static Timestamp time(Instant v) { return v == null ? null : Timestamp.from(v); }
    static Instant instant(Timestamp v) { return v == null ? null : v.toInstant(); }
    static <T extends Enum<T>> T value(Class<T> t,String v) { return v == null ? null : Enum.valueOf(t,v); }
}
