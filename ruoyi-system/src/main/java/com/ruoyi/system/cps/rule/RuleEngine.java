package com.ruoyi.system.cps.rule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.mapper.RuleMapper;
import com.ruoyi.system.cps.mapper.RuleVersionMapper;
import com.ruoyi.union.adapter.UnionOrderLine;
import static com.ruoyi.system.cps.rule.RuleDecision.*;
import static com.ruoyi.system.cps.rule.RuleDocuments.*;

/** Internal application API: authentication/authorization belongs to the caller, not request user IDs.
 * Publication is fail-closed without trusted server safety metadata. No agent application dependency.
 * All timestamps are UTC Instants; publication time is assigned by the database, never the client. */
@Service
public class RuleEngine {
    private final RuleMapper drafts;
    private final RuleVersionMapper versions;
    private final ObjectProvider<RulePorts.Qualification> qualifications;
    private final ObjectProvider<RulePorts.PublicationSafety> safety;

    public RuleEngine(RuleMapper drafts, RuleVersionMapper versions,
            ObjectProvider<RulePorts.Qualification> qualifications,
            ObjectProvider<RulePorts.PublicationSafety> safety) {
        this.drafts = drafts; this.versions = versions;
        this.qualifications = qualifications; this.safety = safety;
    }
    @Transactional
    public Draft create(RulePolicy policy, long operatorId) {
        operator(operatorId); verifyPublication(policy);
        drafts.create(policy.platform().name(), JSON.toJSONString(policy), operatorId);
        return drafts.byPlatform(policy.platform().name());
    }
    @Transactional(readOnly = true)
    public List<Draft> list() { return List.copyOf(drafts.list()); }

    @Transactional(readOnly = true)
    public List<Version> history(long documentId) { return List.copyOf(versions.history(documentId)); }

    @Transactional
    public Draft update(long documentId, long expectedRevision, RulePolicy policy, long operatorId) {
        operator(operatorId); RuleEvaluation.validate(policy);
        Draft draft = locked(documentId, expectedRevision);
        if (!draft.platform().equals(policy.platform().name())) throw new IllegalArgumentException("platform is immutable");
        // Validate every immutable incentive on SAVE too; never accept client-supplied maxCost.
        verifyPublication(policy);
        replace(draft, JSON.toJSONString(policy), false, operatorId);
        return drafts.byPlatform(draft.platform());
    }
    /** Delete means an append-only disabled publication plus archived draft, never erase history. */
    @Transactional
    public void delete(long documentId, long expectedRevision, long operatorId) {
        operator(operatorId); Draft draft = locked(documentId, expectedRevision);
        replace(draft, draft.config(), true, operatorId);
        versions.append(documentId, expectedRevision + 1, draft.config(), false, operatorId, "administrative-disable");
    }
    @Transactional
    public Version publish(long documentId, long expectedRevision, long operatorId) {
        operator(operatorId); Draft draft = locked(documentId, expectedRevision);
        if (draft.archived()) throw new IllegalStateException("archived draft");
        RulePolicy policy = JSON.parseObject(draft.config(), RulePolicy.class);
        String reference = verifyPublication(policy);
        versions.append(documentId, expectedRevision, draft.config(), true, operatorId, reference);
        return versions.history(documentId).getFirst();
    }
    private String verifyPublication(RulePolicy policy) {
        RuleEvaluation.validate(policy);
        RulePorts.PublicationSafety port = safety.getIfAvailable();
        if (port == null) throw new IllegalStateException("trusted server commission limits adapter unavailable");
        RulePorts.Limits limits = Objects.requireNonNull(port.limits(policy));
        if (limits.sourceReference() == null || limits.sourceReference().isBlank()
                || limits.maximumActualCommission() == null || limits.maximumActualCommission().signum() <= 0)
            throw new IllegalStateException("invalid server commission limit evidence");
        if (policy.maximumCommission().compareTo(limits.maximumActualCommission()) > 0)
            throw new IllegalArgumentException("policy exceeds trusted server commission limit");
        return limits.sourceReference();
    }
    /** Trial does not publish, reserve rewards, mutate counters or write order snapshots. */
    public Calculation trial(RulePolicy policy, Facts facts, BigDecimal commission) {
        RuleDecision decision = RuleEvaluation.choose(policy, facts);
        return RuleEvaluation.calculate(new Snapshot(0, facts.buyerUserId(), facts.platform(), null, null,
            facts.itemId(), facts.paidAt(), 0, policy, facts.evidence(), decision), commission);
    }
    @Transactional
    public Snapshot snapshotFor(long orderLineId, long buyerUserId, UnionOrderLine line) {
        if (orderLineId <= 0 || buyerUserId <= 0 || line == null || line.paidAt() == null
                || line.platform() == null || line.orderNo() == null || line.subOrderNo() == null
                || line.paidAt().isAfter(Instant.now())) throw new IllegalArgumentException("invalid order identity/paidAt");
        String existing = versions.snapshot(orderLineId);
        if (existing != null) return identity(JSON.parseObject(existing, Snapshot.class), buyerUserId, line);
        Version version = versions.asOf(line.platform().name(), line.paidAt());
        if (version == null || !version.enabled()) throw new IllegalStateException("no published policy as of paidAt");
        RulePolicy policy = JSON.parseObject(version.config(), RulePolicy.class);
        RulePorts.Qualification provider = qualifications.getIfAvailable();
        Evidence evidence = provider == null ? null : provider.asOf(orderLineId, buyerUserId, line, line.paidAt());
        if (evidence == null) evidence = Evidence.unknown(line.paidAt());
        if (!line.paidAt().equals(evidence.asOf())) throw new IllegalArgumentException("qualification is not as of paidAt");
        if ((evidence.shopId() != null || evidence.categoryId() != null || evidence.jdSelfOperated() != null)
                && (evidence.sourceReference() == null || evidence.sourceReference().isBlank()))
            throw new IllegalStateException("product qualification lacks authoritative source");
        Facts facts = new Facts(buyerUserId, line.platform(), line.itemId(), line.paidAt(), line.estimatedCommission(), evidence);
        Snapshot snapshot = new Snapshot(orderLineId, buyerUserId, line.platform(), line.orderNo(), line.subOrderNo(),
            line.itemId(), line.paidAt(), version.id(), policy, evidence, RuleEvaluation.choose(policy, facts));
        // Validate JD qualification before freezing an unusable snapshot.
        RuleEvaluation.calculate(snapshot, line.estimatedCommission());
        versions.insertSnapshot(orderLineId, buyerUserId, version.id(), JSON.toJSONString(snapshot));
        // A concurrent first writer wins. Locking read avoids an old REPEATABLE READ snapshot.
        return identity(JSON.parseObject(versions.lockedSnapshot(orderLineId), Snapshot.class), buyerUserId, line);
    }
    /** Uses persisted full configuration, NOT the current version or current qualification data. */
    @Transactional(readOnly = true)
    public Calculation calculate(Snapshot snapshot, BigDecimal actualCommission) {
        Objects.requireNonNull(snapshot);
        String stored = versions.snapshot(snapshot.orderLineId());
        if (stored == null) throw new IllegalArgumentException("snapshot is not persisted");
        Snapshot authoritative = JSON.parseObject(stored, Snapshot.class);
        if (!authoritative.equals(snapshot)) throw new IllegalArgumentException("snapshot differs from persisted decision");
        return RuleEvaluation.calculate(authoritative, actualCommission);
    }
    private Snapshot identity(Snapshot snapshot, long buyer, UnionOrderLine line) {
        if (snapshot.buyerUserId() != buyer || snapshot.platform() != line.platform()
                || !Objects.equals(snapshot.orderNo(), line.orderNo()) || !Objects.equals(snapshot.subOrderNo(), line.subOrderNo())
                || !Objects.equals(snapshot.itemId(), line.itemId()) || !snapshot.paidAt().equals(line.paidAt()))
            throw new IllegalStateException("immutable order snapshot identity conflict; audit attribution correction separately");
        return snapshot;
    }
    private Draft locked(long id, long revision) {
        Draft draft = drafts.lock(id);
        if (draft == null || draft.revision() != revision) throw new IllegalStateException("draft revision conflict");
        return draft;
    }
    private void replace(Draft draft, String config, boolean archived, long operator) {
        if (drafts.replace(draft.id(), draft.revision(), config, archived, operator) != 1)
            throw new IllegalStateException("draft revision conflict");
    }
    private static void operator(long id) {
        if (id <= 0) throw new IllegalArgumentException("authenticated operator required");
    }
}
