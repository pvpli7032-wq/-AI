package com.ruoyi.system.cps.settlement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Comparator;
import com.ruoyi.system.cps.rule.RewardApi;
import com.ruoyi.system.cps.rule.RewardCalculator;
import com.ruoyi.system.cps.rule.RewardService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.contract.*;
import com.ruoyi.system.cps.ledger.*;
import com.ruoyi.system.cps.settlement.SettlementRepository.Item;
import com.ruoyi.system.cps.settlement.SettlementRepository.Position;
import com.ruoyi.system.cps.settlement.SettlementTermsPort.Benefit;
import com.ruoyi.system.cps.settlement.SettlementTermsPort.Terms;

/** Atomic event receipt + immutable rule snapshot + funds + state + mandatory notification enqueue.
 * Locks: order row, settlement cursor, items ordered by user/id, then ledger accounts in user order.
 * Never trusts upstream line_status as the settlement cursor.
 */
@Service
public class DefaultSettlementService implements SettlementService {
    private final SettlementRepository repository;
    private final LedgerService ledger;
    private final ObjectProvider<SettlementTermsPort> terms;
    private final ObjectProvider<SettlementFreezePort> freezes;
    private final SettlementExternalServices external;
    private final RewardService rewards;
    private final ObjectProvider<SettlementRewardTermsPort> rewardTerms;
    private final TransactionTemplate tx;

    public DefaultSettlementService(SettlementRepository repository, LedgerService ledger,
            ObjectProvider<SettlementTermsPort> terms, ObjectProvider<SettlementFreezePort> freezes,
            SettlementExternalServices external, RewardService rewards, ObjectProvider<SettlementRewardTermsPort> rewardTerms,
            PlatformTransactionManager manager) {
        this.repository=repository; this.ledger=ledger; this.terms=terms; this.freezes=freezes; this.external=external;
        this.rewards=rewards; this.rewardTerms=rewardTerms;
        tx=new TransactionTemplate(manager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Override
    public void onOrderEvent(OrderEvent event) {
        validate(event);
        tx.executeWithoutResult(status -> process(event));
    }
    private void process(OrderEvent e) {
        List<Transfer> transfers=new ArrayList<>();
        Map<String,Object> order=repository.lockOrder(e.orderLineId());
        if (!Objects.equals(order.get("order_id"),e.line().orderNo())
                || !Objects.equals(order.get("sub_order_no"),e.line().subOrderNo() == null ? "" : e.line().subOrderNo())
                || !e.line().platform().name().equalsIgnoreCase(String.valueOf(order.get("platform_type"))))
            throw new IllegalArgumentException("Settlement event order identity mismatch");
        String fingerprint=fingerprint(e);
        String prior=repository.receipt(e.eventKey());
        if (prior != null) {
            if (!prior.equals(fingerprint)) throw new IllegalArgumentException("Settlement event key payload mismatch");
            return;
        }
        Position p=repository.load(e.orderLineId());
        if (p == null && repository.legacy(e.orderLineId(),order)) {
            repository.manual(e.orderLineId(),e.eventKey(),"LEGACY_ACCOUNTED_REQUIRES_MANUAL_MIGRATION"); return;
        }
        if (p == null) { p=new Position(); p.id=e.orderLineId(); }
        // Unknown loss evidence is a committed safety decision, not a rolled-back exception.
        // It must precede repricing, reward changes, funding and dispute restoration.
        if ((e.toStatus()==OrderStatus.PARTIAL_REFUND || e.toStatus()==OrderStatus.PENALIZED)
                && e.recoveredAmount()==null) {
            holdForManualReview(p,e,fingerprint,"MISSING_CUMULATIVE_ALLIANCE_COMMISSION_RECOVERY");
            return;
        }
        if (p.manualHold) {
            holdForManualReview(p,e,fingerprint,"MANUAL_SETTLEMENT_HOLD_ACTIVE");
            return;
        }
        if (p.lastEventAt!=null && e.occurredAt().isBefore(p.lastEventAt)) {
            repository.manual(p.id,e.eventKey(),"STALE_ALLIANCE_EVENT_REQUIRES_RECONCILIATION"); return;
        }
        if (!legal(p,e)) {
            if (e.toStatus()==OrderStatus.INVALID) {
                // The shared contract does not permit RECEIVED/SETTLED -> INVALID.
                // Preserve its state and quarantine future releases pending an authorized correction.
                holdForManualReview(p,e,fingerprint,"ILLEGAL_INVALID_TRANSITION_REQUIRES_MANUAL_REVIEW");
                return;
            }
            // Quarantine disputed money even when the shared ORDER contract cannot express
            // this edge. Do not invent an ORDER transition or silently release the funds.
            if (e.toStatus()==OrderStatus.DISPUTED && p.terms!=null) {
                for (Item item:repository.items(p.id)) if ("FROZEN".equals(item.phase) && item.paused==null) {
                    item.paused=e.occurredAt(); item.clock=item.clock.pause(e.occurredAt());
                    item.status=RebateStatus.PAUSED; repository.save(item);
                }
            }
            repository.manual(p.id,e.eventKey(),"ILLEGAL_ORDER_TRANSITION_OR_DISPUTE_RESTORATION"); return;
        }
        // Attribution is historical order ownership, never the current invitation graph.
        Long buyer=order.get("buyer_user_id") instanceof Number n ? n.longValue() : null;
        if (p.buyer != null && !p.buyer.equals(buyer)) {
            repository.manual(p.id,e.eventKey(),"ATTRIBUTION_CHANGED_AFTER_SNAPSHOT"); return;
        }
        if (buyer == null || buyer <= 0) {
            p.status=e.toStatus(); p.lastEventAt=e.occurredAt(); repository.save(p); repository.order(p);
            repository.manual(p.id,e.eventKey(),"UNOWNED_PENDING_LATE_ATTRIBUTION_OR_PLATFORM_EXPIRY");
            // No receipt yet: a historical attribution arriving later may replay this exact event.
            return;
        }
        if (p.terms == null) {
            p.buyer=buyer;
            var invitation=external.invitation(p.id,buyer,e.line());
            p.referralManual=invitation.manualReview(); p.evidence=invitation.evidenceId();
            Terms snapshot=terms().snapshot(p.id,buyer,e.line(),p.referralManual ? null : invitation.inviterUserId());
            validateBeneficiaries(snapshot,buyer,invitation.inviterUserId(),p.referralManual);
            p.terms=snapshot;
            SettlementRewardTermsPort rewardAdapter=rewardTerms.getIfAvailable();
            if (rewardAdapter==null) throw new IllegalStateException("SettlementRewardTermsPort adapter pending; manual settlement required");
            RewardApi.Snapshot rewardSnapshot=rewardAdapter.snapshot(p.id,buyer,e.line(),p.referralManual ? null : invitation.inviterUserId(),p.referralManual);
            if (rewardSnapshot.orderId()!=p.id || rewardSnapshot.buyerId()!=buyer || rewardSnapshot.historicalUnknown()!=p.referralManual
                    || !Objects.equals(rewardSnapshot.inviterId(),p.referralManual ? null : invitation.inviterUserId()))
                throw new IllegalArgumentException("Reward adapter changed historical attribution");
            validateRewardOwnership(snapshot,rewardSnapshot);
            rewards.capture(rewardSnapshot);
            p.rewardPending=!p.referralManual; p.rewardRetryAt=e.occurredAt();
            if (p.referralManual) repository.manual(p.id,e.eventKey(),"INVITATION_HISTORY_UNKNOWN_BUYER_ONLY");
            for (Benefit b:snapshot.benefits()) repository.insert(p.id,b);
        }
        List<Item> items=repository.items(p.id);
        boolean funding=List.of(OrderStatus.RECEIVED,OrderStatus.SETTLED,OrderStatus.PARTIAL_REFUND,OrderStatus.PENALIZED).contains(e.toStatus());
        if (funding) {
            if (e.line().actualCommission() == null) throw new IllegalStateException("Actual alliance commission required before funding");
            reprice(p,items,e,transfers);
            for (Item i:items) {
                if ("NONE".equals(i.phase) && i.status != RebateStatus.VOID) {
                    Benefit b=benefit(p.effectiveTerms(),i);
                    Duration duration=freeze().duration(p.effectiveTerms(),e.line(),b);
                    if (duration == null || duration.isNegative()) throw new IllegalStateException("Invalid configured freeze duration");
                    i.clock=FreezeClock.start(e.occurredAt(),duration);
                    i.due=i.clock.dueAt().orElseThrow(); i.phase="FROZEN"; i.status=RebateStatus.FROZEN; i.newlyFrozen=true;
                }
            }
        }
        if (e.toStatus() == OrderStatus.FULL_REFUND && p.commission == null && e.line().actualCommission()!=null)
            reprice(p,items,e,transfers);
        if (e.toStatus() == OrderStatus.DISPUTED && p.status != OrderStatus.DISPUTED) {
            p.previous=p.status == null ? e.previousStatus() : p.status;
            for (Item i:items) if ("FROZEN".equals(i.phase) && i.paused == null) {
                i.paused=e.occurredAt();
                i.clock=i.clock.pause(e.occurredAt());
                if (!i.clock.paused() && !i.clock.remainingAt(e.occurredAt()).isZero())
                    i.clock=new FreezeClock(i.clock.remainingAt(e.occurredAt()),e.occurredAt(),true);
                i.status=RebateStatus.PAUSED;
            }
        } else if (p.status == OrderStatus.DISPUTED && e.toStatus()!=OrderStatus.DISPUTED) {
            for (Item i:items) if (i.paused != null) {
                if (e.occurredAt().isBefore(i.paused)) throw new IllegalArgumentException("Dispute resolution predates pause");
                i.clock=i.clock.resume(e.occurredAt());
                if (i.clock.paused()) throw new IllegalArgumentException("Dispute resolution must follow pause time");
                i.due=i.clock.dueAt().orElseThrow(); i.paused=null;
                i.status=i.recovered.signum()>0 ? RebateStatus.PARTIALLY_RECOVERED : RebateStatus.FROZEN;
            }
            p.previous=null;
        }
        BigDecimal recovery=e.toStatus()==OrderStatus.INVALID && p.commission!=null
            ? p.commission : p.recovered;
        if (List.of(OrderStatus.PARTIAL_REFUND,OrderStatus.PENALIZED,OrderStatus.FULL_REFUND).contains(e.toStatus())) {
            if (e.recoveredAmount() == null) throw new IllegalArgumentException("Cumulative alliance commission recovery required");
            recovery=e.recoveredAmount();
            if (p.commission == null) p.commission=e.line().actualCommission();
            if (p.commission == null || recovery.compareTo(p.recovered)<0 || recovery.compareTo(p.commission)>0)
                throw new IllegalArgumentException("Invalid cumulative alliance commission recovery");
            if (e.toStatus()==OrderStatus.FULL_REFUND && recovery.compareTo(p.commission)!=0)
                throw new IllegalArgumentException("Full refund must recover original alliance commission");
        }
        List<BigDecimal> targets=recovery.signum()==0 ? items.stream().map(i -> BigDecimal.ZERO.setScale(2)).toList()
            : CumulativeRecoveryAllocator.cumulativeTargets(p.commission,recovery,items.stream().map(i -> i.original).toList());
        List<BigDecimal> deltas=CumulativeRecoveryAllocator.differences(targets,items.stream().map(i -> i.recovered).toList());
        // All ledger work for one user finishes before locking the next user's account.
        for (int index=0; index<items.size(); index++) {
            Item i=items.get(index);
            if (i.newlyFrozen)
                post(transfers,p,i,e.eventKey(),"FREEZE",i.original,cost(i),AccountType.USER_FROZEN);
            else if (i.adjustment.signum()>0)
                post(transfers,p,i,e.eventKey(),"REPRICE_UP",i.adjustment,cost(i),pocket(i));
            else if (i.adjustment.signum()<0)
                post(transfers,p,i,e.eventKey(),"REPRICE_DOWN",i.adjustment.negate(),pocket(i),cost(i));
            BigDecimal delta=deltas.get(index);
            if (delta.signum()>0 && !"NONE".equals(i.phase))
                post(transfers,p,i,e.eventKey(),e.toStatus()==OrderStatus.PENALIZED ? "PENALTY_RECOVER" : "RECOVER",delta,pocket(i),cost(i));
            i.recovered=targets.get(index);
            boolean full=i.recovered.compareTo(i.original)==0 && recovery.signum()>0;
            if (full || e.toStatus()==OrderStatus.INVALID || e.toStatus()==OrderStatus.FULL_REFUND) {
                i.status="AVAILABLE".equals(i.phase) ? RebateStatus.FULLY_RECOVERED : RebateStatus.VOID;
            } else if (delta.signum()>0) i.status=RebateStatus.PARTIALLY_RECOVERED;
            repository.save(i);
        }
        if (recovery.signum()>0) {
            RewardApi.Result result=rewards.refund(p.id,RewardApi.Refund.fromCommissionRecovery(recovery,p.commission,
                Objects.requireNonNull(e.line().paidAmount(),"Alliance paid amount required"),p.commission.subtract(recovery)));
            rewardTransfers(transfers,result.instructions(),
                e.toStatus()==OrderStatus.PENALIZED ? "PENALTY_RECOVER" : "RECOVER");
            if (result.manualReview()) repository.manual(p.id,e.eventKey(),"REWARD_HISTORY_REQUIRES_MANUAL_REVIEW");
        }
        // Terminal invalidity is authoritative even when no positive commission was
        // ever funded. Zero on PAID/RECEIVED alone is NOT terminal evidence.
        if (e.toStatus()==OrderStatus.INVALID || e.toStatus()==OrderStatus.FULL_REFUND && recovery.signum()==0) {
            rewards.excludeInvalid(p.id,new RewardApi.Qualification(e.occurredAt(),e.occurredAt(),true,false,
                repository.paidAmount(p.id),BigDecimal.ZERO,BigDecimal.ZERO));
            p.rewardPending=false;
        } else if (recovery.signum()>0 && recovery.compareTo(p.commission)==0) p.rewardPending=false;
        apply(transfers);
        p.recovered=recovery; p.status=e.toStatus(); p.lastEventAt=e.occurredAt();
        p.reportedCommission=p.commission == null ? e.line().estimatedCommission() : p.commission;
        if (p.reportedCommission==null || p.reportedCommission.signum()<0)
            throw new IllegalArgumentException("Reported alliance commission required");
        repository.save(p); repository.order(p);
        repository.receipt(e.eventKey(),p.id,fingerprint);
    }
    private void holdForManualReview(Position p,OrderEvent e,String fingerprint,String reason) {
        for (Item item:repository.items(p.id)) {
            if (!"FROZEN".equals(item.phase) || item.paused!=null
                    || item.status==RebateStatus.VOID || item.status==RebateStatus.FULLY_RECOVERED) continue;
            Instant heldAt=item.clock!=null && e.occurredAt().isBefore(item.clock.anchor())
                ? item.clock.anchor() : e.occurredAt();
            item.paused=heldAt;
            // Even an already-mature, not-yet-released balance stays quarantined.
            if (item.clock!=null)
                item.clock=new FreezeClock(item.clock.remainingAt(heldAt),heldAt,true);
            item.status=RebateStatus.PAUSED;
            repository.save(item);
        }
        p.manualHold=true;
        repository.save(p);
        repository.holdEvent(p.id,e.eventKey(),reason,JSON.toJSONString(e));
        repository.manual(p.id,e.eventKey(),reason);
        repository.receipt(e.eventKey(),p.id,fingerprint);
    }
    private void reprice(Position p,List<Item> items,OrderEvent e,List<Transfer> transfers) {
        BigDecimal actual=e.line().actualCommission();
        if (actual.signum()<0 || actual.compareTo(p.terms.maxCommission())>0)
            throw new IllegalArgumentException("Actual commission exceeds snapshot ceiling");
        if (p.commission != null && p.commission.compareTo(actual)==0) return;
        if (p.recovered.signum()>0) throw new IllegalStateException("Repricing after recovery requires manual reconciliation");
        Terms recalculated=terms().recalculate(p.terms,e.line());
        if (!p.terms.maxCommission().equals(recalculated.maxCommission())
                || !p.terms.freezePolicy().equals(recalculated.freezePolicy())
                || !p.terms.ruleSnapshotJson().equals(recalculated.ruleSnapshotJson()) || recalculated.benefits().size()!=items.size())
            throw new IllegalArgumentException("Actual recalculation changed locked terms");
        if (p.commission!=null) {
            BigDecimal rebate=recalculated.benefits().stream().filter(b -> b.type().equals("REBATE"))
                .map(Benefit::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
            Instant freezeUntil=items.stream().map(i -> i.due).filter(Objects::nonNull).max(Instant::compareTo)
                .orElse(e.occurredAt());
            boolean released=items.stream().allMatch(i -> "AVAILABLE".equals(i.phase) || i.original.signum()==0);
            // Propagate fail-closed award repricing in this transaction: ordinary money must
            // never be adjusted while already-qualified variable awards retain their old basis.
            RewardApi.Result repriced=rewards.reprice(p.id,new RewardApi.Qualification(e.occurredAt(),freezeUntil,released,
                items.stream().anyMatch(i -> i.paused!=null),
                Objects.requireNonNull(e.line().paidAmount(),"Alliance paid amount required"),actual,rebate));
            rewardTransfers(transfers,repriced.instructions());
            if (repriced.manualReview()) repository.manual(p.id,e.eventKey(),"REWARD_REPRICING_REQUIRES_MANUAL_REVIEW");
        }
        for (Item i:items) {
            Benefit b=benefit(recalculated,i);
            if (!Objects.equals(b.ruleVersionId(),i.version)) throw new IllegalArgumentException("Actual recalculation changed rule version");
            if (!"NONE".equals(i.phase)) i.adjustment=b.amount().subtract(i.original);
            i.original=b.amount(); // Actual immutable recovery base, never the shrinking remaining entitlement.
            // Existing funding is adjusted in the sorted posting loop, not here (prevents crossed account locks).
        }
        p.actualTerms=recalculated; p.commission=actual;
    }
    private Benefit benefit(Terms t,Item i) {
        return t.benefits().stream().filter(b -> b.type().equals(i.type) && b.beneficiaryUserId()==i.user).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Actual recalculation changed benefit identity"));
    }
    private void validateBeneficiaries(Terms t,long buyer,Long inviter,boolean manual) {
        if (t.benefits().stream().noneMatch(b -> b.type().equals("REBATE")))
            throw new IllegalArgumentException("Snapshot requires a buyer rebate row, including zero-value entitlement");
        for (Benefit b:t.benefits()) {
            if (manual && !b.type().equals("REBATE"))
                throw new IllegalArgumentException("Unknown invitation history permits buyer rebate only");
            boolean referral=b.type().equals("REFERRAL");
            if (referral ? manual || !Objects.equals(b.beneficiaryUserId(),inviter) : b.beneficiaryUserId()!=buyer)
                throw new IllegalArgumentException("Rule adapter returned unauthorized beneficiary");
        }
    }
    private void validateRewardOwnership(Terms settlement,RewardApi.Snapshot reward) {
        boolean referral=settlement.benefits().stream().anyMatch(b -> b.type().equals("REFERRAL") && b.amount().signum()>0);
        boolean subsidy=settlement.benefits().stream().anyMatch(b -> b.type().equals("SUBSIDY") && b.amount().signum()>0);
        if (reward.configs().stream().anyMatch(c -> c.applies(reward) &&
                (referral && c.type()==RewardApi.Type.INVITATION_RATIO || subsidy && c.type()==RewardApi.Type.FIRST_N_SUBSIDY)))
            throw new IllegalArgumentException("Rule and reward adapters overlap entitlement ownership");
    }
    private boolean legal(Position p,OrderEvent e) {
        if (p.status==null) return e.toStatus()!=OrderStatus.DISPUTED
            || e.previousStatus()==OrderStatus.RECEIVED || e.previousStatus()==OrderStatus.SETTLED;
        if (p.status==e.toStatus()) return true; // same real phase may carry newer cumulative commission evidence
        if (e.fromStatus()!=null && p.status!=e.fromStatus()) return false;
        // The existing edge table does not enumerate subsequent cumulative loss observations.
        // Retain fund_phase; never restore a terminal loss to a funded ORDER phase.
        boolean cumulativeLoss=(p.status==OrderStatus.PARTIAL_REFUND || p.status==OrderStatus.PENALIZED)
            && (e.toStatus()==OrderStatus.PARTIAL_REFUND || e.toStatus()==OrderStatus.PENALIZED || e.toStatus()==OrderStatus.FULL_REFUND);
        if (!cumulativeLoss && !StatusTransitions.ORDER.getOrDefault(p.status,java.util.Set.of()).contains(e.toStatus())) return false;
        return p.status!=OrderStatus.DISPUTED || !(e.toStatus()==OrderStatus.RECEIVED || e.toStatus()==OrderStatus.SETTLED)
            || e.previousStatus()==p.previous && e.toStatus()==p.previous;
    }
    private record Transfer(long user,long order,String key,String flow,String phase,BigDecimal amount,AccountType debit,AccountType credit) {}
    private void post(List<Transfer> transfers,Position p,Item i,String event,String phase,BigDecimal amount,AccountType debit,AccountType credit) {
        if (amount.signum()==0) return;
        String key="st:"+i.id+":"+digest(event)+":"+phase;
        String flow=phase.endsWith("RECOVER") ? (i.type.equals("REFERRAL") ? "refund_promotion" : "refund_self")
            : phase.equals("RELEASE") ? "settle_release" : "settle_freeze";
        transfers.add(new Transfer(i.user,p.id,key,flow,phase,amount,debit,credit));
    }
    private void rewardTransfers(List<Transfer> transfers,List<RewardApi.Instruction> instructions) {
        rewardTransfers(transfers,instructions,"RECOVER");
    }
    private void rewardTransfers(List<Transfer> transfers,List<RewardApi.Instruction> instructions,String recoveryPhase) {
        for (RewardApi.Instruction instruction:instructions) {
            String key=instruction.businessKey();
            if (key.length()>128) throw new IllegalArgumentException("Reward business key exceeds ledger limit");
            // The original award order, NOT the refunded contribution order, is the accounting link.
            transfers.add(new Transfer(instruction.userId(),instruction.awardOrderId(),key,
                instruction.recovery() ? "reward_recovery" : "reward_credit",
                instruction.recovery() ? (key.startsWith("RP:") ? "REPRICE_DOWN" : recoveryPhase) : "REWARD",
                instruction.amount(),instruction.recovery() ? AccountType.USER_AVAILABLE : AccountType.REWARD_COST,
                instruction.recovery() ? AccountType.REWARD_COST : AccountType.USER_AVAILABLE));
        }
    }
    private void apply(List<Transfer> transfers) {
        // Preserve phases within each user (freeze before recovery), while globally sorting all item/reward users.
        transfers.sort(Comparator.comparingLong(Transfer::user));
        for (Transfer t:transfers) {
            if ((t.debit()==AccountType.REBATE_COST || t.debit()==AccountType.REWARD_COST) && t.credit()==AccountType.USER_AVAILABLE) {
                String fundingKey=t.key()+":F";
                if (fundingKey.length()>128) throw new IllegalArgumentException("Reward funding business key exceeds ledger limit");
                LedgerPosting funding=ledger.post(new PostingCommand(fundingKey,"CPS_SETTLEMENT",fundingKey,t.debit(),t.user(),t.order(),
                    "reward_freeze",t.debit(),AccountType.USER_FROZEN,t.amount(),"REWARD_FREEZE",null));
                external.notify(fundingKey,t.user(),t.order(),"REWARD_FREEZE",BigDecimal.ZERO,funding.balanceAfter());
                t=new Transfer(t.user(),t.order(),t.key(),t.flow(),t.phase(),t.amount(),AccountType.USER_FROZEN,AccountType.USER_AVAILABLE);
            }
            LedgerPosting result=ledger.post(new PostingCommand(t.key(),"CPS_SETTLEMENT",t.key(),t.debit(),t.user(),t.order(),
                t.flow(),t.debit(),t.credit(),t.amount(),t.phase(),null));
            // Posting and its outbox entry commit atomically. Replayed historical instructions
            // must not re-label the original loss (e.g. a penalty replayed by a later refund).
            if (!result.replayed()) external.notify(t.key(),t.user(),t.order(),t.phase(),
                t.phase().endsWith("RECOVER") || t.phase().equals("REPRICE_DOWN")
                    ? t.amount() : BigDecimal.ZERO,result.balanceAfter());
        }
    }
    private AccountType cost(Item i) { return i.type.equals("REBATE") ? AccountType.REBATE_COST : AccountType.REWARD_COST; }
    private AccountType pocket(Item i) { return "AVAILABLE".equals(i.phase) ? AccountType.USER_AVAILABLE : AccountType.USER_FROZEN; }
    private SettlementTermsPort terms() {
        SettlementTermsPort result=terms.getIfAvailable();
        if (result==null) throw new IllegalStateException("SettlementTermsPort adapter pending; manual settlement required"); return result;
    }
    private SettlementFreezePort freeze() {
        SettlementFreezePort result=freezes.getIfAvailable();
        if (result==null) throw new IllegalStateException("SettlementFreezePort adapter pending; manual settlement required"); return result;
    }
    public int unfreezeDue(Instant now,int limit) {
        if (now==null || limit<1 || limit>1000) throw new IllegalArgumentException("Invalid unfreeze batch");
        int processed=0;
        for (Long id:repository.due(now,limit)) {
            tx.executeWithoutResult(status -> release(id,now)); processed++;
        }
        return processed;
    }
    private void release(long id,Instant now) {
        repository.lockOrder(id);
        Position p=repository.load(id);
        if (p==null || p.manualHold || p.status==OrderStatus.DISPUTED) return;
        if (p.status==OrderStatus.INVALID || p.status==OrderStatus.FULL_REFUND && p.recovered.signum()==0) {
            rewards.excludeInvalid(p.id,new RewardApi.Qualification(now,now,true,false,
                repository.paidAmount(p.id),BigDecimal.ZERO,BigDecimal.ZERO));
            p.rewardPending=false; repository.save(p); return;
        }
        List<Transfer> transfers=new ArrayList<>();
        List<Item> items=repository.items(id);
        Instant latestDue=items.stream().map(i -> i.due).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(now);
        for (Item i:items) {
            if (!"FROZEN".equals(i.phase) || i.paused!=null || i.due==null || i.due.isAfter(now)
                    || i.clock==null || !i.clock.isMature(now)
                    || !(i.status==RebateStatus.FROZEN || i.status==RebateStatus.PARTIALLY_RECOVERED)) continue;
            post(transfers,p,i,"unfreeze", "RELEASE",i.original.subtract(i.recovered),AccountType.USER_FROZEN,AccountType.USER_AVAILABLE);
            i.phase="AVAILABLE"; i.status=RebateStatus.CREDITED; repository.save(i);
        }
        boolean released=items.stream().allMatch(i -> "AVAILABLE".equals(i.phase) || i.status==RebateStatus.VOID);
        if (released && p.terms!=null && p.commission!=null) {
            BigDecimal rebate=items.stream().filter(i -> i.type.equals("REBATE")).map(i -> i.original.subtract(i.recovered)).reduce(BigDecimal.ZERO,BigDecimal::add);
            RewardApi.Qualification evidence=new RewardApi.Qualification(now,latestDue,true,false,
                repository.paidAmount(p.id),p.commission.subtract(p.recovered),rebate);
            // Only SETTLED is final commission proof. A received/provisional zero or
            // below-threshold observation stays pending for a later alliance update.
            RewardApi.Snapshot snapshot=rewards.snapshot(p.id);
            boolean finalInvalid=p.status==OrderStatus.SETTLED && snapshot!=null
                && !RewardCalculator.valid(snapshot,evidence.paid(),evidence.commission());
            if (finalInvalid) {
                rewards.excludeInvalid(p.id,evidence); p.rewardPending=false;
            } else if (p.recovered.compareTo(p.commission)<0) {
                RewardApi.Result result=rewards.qualify(p.id,evidence);
                rewardTransfers(transfers,result.instructions());
                if (result.manualReview()) repository.manual(p.id,"unfreeze","REWARD_HISTORY_REQUIRES_MANUAL_REVIEW");
            }
        }
        // Persist retry scheduling in the same transaction as release/reward postings.
        // Successful/closed reward states are filtered by discovery; deferrals survive
        // after every ordinary settlement item has become AVAILABLE.
        p.rewardRetryAt=now.plusSeconds(60); repository.save(p);
        apply(transfers);
    }
    private static void validate(OrderEvent e) {
        if (e==null || e.orderLineId()==null || e.orderLineId()<=0 || e.line()==null || e.line().platform()==null
                || e.toStatus()==null || e.occurredAt()==null || e.eventKey()==null || e.eventKey().isBlank() || e.eventKey().length()>128)
            throw new IllegalArgumentException("Incomplete real alliance event");
    }
    static String fingerprint(OrderEvent e) { return digest(JSON.toJSONString(e)); }
    static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
