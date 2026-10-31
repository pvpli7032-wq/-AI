package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decisions are immutable. A separate pointer and the order's buyer fields are
 * changed under the order-row lock; no financial columns are written here.
 */
@Service
public class OrderAttributionService implements AttributionService {
    private final JdbcTemplate jdbc;
    private final PidPoolService pids;
    private final AttributionTokenService tokens;
    private final AttributionSettings settings;
    private final InviteBindingService invitations;
    private final ObjectProvider<SettlementService> settlement;
    private final TransactionTemplate transactions;
    private static final int TOKEN=40, CLAIM=30, LEASE=20, MANUAL=100, PLATFORM=90;
    private record Decision(long id, AttributionDecision value, int priority, String reason) {}
    private record OrderRow(long id, UnionOrderLine line, Long buyer, String status, String legacyStatus) {}

    public OrderAttributionService(JdbcTemplate jdbc, PidPoolService pids, AttributionTokenService tokens,
        AttributionSettings settings, InviteBindingService invitations, ObjectProvider<SettlementService> settlement, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.pids=pids; this.tokens=tokens; this.settings=settings;
        this.invitations=invitations; this.settlement=settlement; this.transactions=new TransactionTemplate(manager);
    }

    @Override public AttributionDecision decide(Long orderLineId, UnionOrderLine line) {
        if(orderLineId==null || line==null || line.platform()==null) throw new ServiceException("缺少订单行");
        return transactions.execute(tx -> {
            OrderRow row=lockOrder(orderLineId);
            if(!row.line.platform().equals(line.platform()) || !row.line.orderNo().equals(line.orderNo()) ||
                !row.line.subOrderNo().equals(line.subOrderNo()==null?"":line.subOrderNo()))
                throw new ServiceException("订单行与联盟证据不一致");
            Decision current=initialCurrent(row);
            Instant now=AttributionData.now(jdbc);
            current=expireIfDue(row,current,now);
            if(current!=null && current.priority>=PLATFORM) return current.value;
            if(pids.typeOf(line.platform(),line.pid())==PidType.BLOCKED)
                return unattributed(row,current,"BLOCKED_PID",now,"BLOCKED");
            if(invalid(row)) return unattributed(row,current,"INVALID_ORDER",now,"OPEN");
            List<Long> claimants=claimants(line.platform(),line.orderNo(),line.paidAt(),now);
            if(claimants.size()>1) return conflict(row,current,"CLAIM_CONFLICT",null,now);
            Long tokenUser=line.platform()==Platform.TBK ? null : tokens.resolve(line.platform(),line.itemId(),line.attributionToken(),line.paidAt());
            if(tokenUser!=null) return accept(row,current,new AttributionDecision(tokenUser,AttributionMethod.ATTRIBUTION_TOKEN,AttributionConfidence.HIGH,null),TOKEN,"PLATFORM_TOKEN",now,false);
            if(claimants.size()==1 && AttributionData.validUser(jdbc,claimants.get(0))) {
                AttributionDecision result=accept(row,current,new AttributionDecision(claimants.get(0),AttributionMethod.CLAIM,AttributionConfidence.MEDIUM,claimants.get(0)),CLAIM,"PENDING_CLAIM",now,false);
                if(Objects.equals(result.userId(),claimants.get(0))) jdbc.update("UPDATE cps_order_claim SET status='BOUND',reason='BOUND',updated_at=? WHERE platform=? AND order_no=? AND user_id=?",AttributionData.timestamp(now),line.platform().name(),line.orderNo(),claimants.get(0));
                return result;
            }
            if(line.platform()==Platform.TBK && pids.typeOf(line.platform(),line.pid())==PidType.TRACKING && line.paidAt()!=null && line.itemId()!=null) {
                List<Long> users=jdbc.queryForList("SELECT DISTINCT user_id FROM cps_pid_lease WHERE platform=? AND BINARY pid=BINARY ? AND BINARY item_id=BINARY ? AND start_at<=? AND expire_at>?",Long.class,
                    line.platform().name(),line.pid(),line.itemId(),AttributionData.timestamp(line.paidAt()),AttributionData.timestamp(line.paidAt()));
                if(users.size()>1) return conflict(row,current,"LEASE_CONFLICT",null,now);
                if(users.size()==1 && AttributionData.validUser(jdbc,users.get(0)))
                    return accept(row,current,new AttributionDecision(users.get(0),AttributionMethod.TRACKING_PID,AttributionConfidence.MEDIUM,null),LEASE,"TRACKING_PID",now,false);
            }
            String reason=pids.typeOf(line.platform(),line.pid())==PidType.DEFAULT ? "DEFAULT_PID" : "NO_EVIDENCE";
            if(line.itemId()!=null && line.paidAt()!=null) {
                // The shared enum has no ITEM_MATCH value. Preserve a candidate reason,
                // never mislabel it as TRACKING_PID or silently invent a shared contract.
                jdbc.update("INSERT IGNORE INTO cps_attribution_candidate(order_line_id,user_id,query_log_id,created_at) SELECT ?,q.user_id,q.id,? FROM cps_query_log q JOIN sys_user u ON u.user_id=q.user_id WHERE q.platform=? AND BINARY q.item_id=BINARY ? AND q.result='SUCCESS' AND q.created_at>=? AND q.created_at<=? AND u.status='0' AND u.del_flag='0'",row.id,AttributionData.timestamp(now),line.platform().name(),line.itemId(),AttributionData.timestamp(line.paidAt().minus(settings.matchDuration())),AttributionData.timestamp(line.paidAt()));
                Long candidates=jdbc.queryForObject("SELECT COUNT(DISTINCT user_id) FROM cps_attribution_candidate WHERE order_line_id=?",Long.class,row.id);
                if(candidates>0 && !"DEFAULT_PID".equals(reason)) reason=candidates>1?"AMBIGUOUS_ITEM_CANDIDATES":"ITEM_MATCH_CONTRACT_PENDING";
            }
            return unattributed(row,current,reason,now,"OPEN");
        });
    }

    public ClaimResult claim(Long userId, Platform platform, String orderNo) {
        AttributionData.requireUser(jdbc,userId); Objects.requireNonNull(platform);
        AttributionData.text(orderNo,100,"订单号");
        Duration window=settings.claimDuration();
        return transactions.execute(tx -> {
            // The mutex also serializes claims submitted before an order is imported.
            jdbc.update("INSERT INTO cps_claim_mutex(platform,order_no) VALUES (?,?) ON DUPLICATE KEY UPDATE order_no=VALUES(order_no)",platform.name(),orderNo);
            jdbc.queryForObject("SELECT order_no FROM cps_claim_mutex WHERE platform=? AND order_no=? FOR UPDATE",String.class,platform.name(),orderNo);
            List<OrderRow> rows=jdbc.query("SELECT * FROM sys_order_commission WHERE UPPER(platform_type)=? AND order_id=? ORDER BY id FOR UPDATE",this::mapOrder,platform.name(),orderNo);
            Instant now=AttributionData.now(jdbc);
            if(rows.isEmpty()) {
                recordClaim(userId,platform,orderNo,"PENDING","NOT_FOUND",now,now.plus(window));
                return result("NOT_FOUND","未找到订单；联盟尚未返回或订单被其他红包、礼金归走",orderNo,List.of());
            }
            List<Long> ids=rows.stream().map(OrderRow::id).toList();
            for(OrderRow row:rows) {
                expireIfDue(row,initialCurrent(row),now);
                if(pids.typeOf(platform,row.line.pid())==PidType.BLOCKED)
                    return rejectClaim(userId,platform,orderNo,"BLOCKED_PID","该推广位的订单不可认领",now,window);
                if(invalid(row)) return rejectClaim(userId,platform,orderNo,"INVALID_ORDER","订单已失效",now,window);
                if(row.line.paidAt()==null) return rejectClaim(userId,platform,orderNo,"ORDER_TIME_MISSING","订单时间尚未同步，请稍后重试",now,window);
                if(now.isAfter(row.line.paidAt().plus(window)) || platformOwned(row.id))
                    return rejectClaim(userId,platform,orderNo,"EXPIRED","已超过认领时间",now,window);
            }
            List<Long> others=jdbc.queryForList("SELECT DISTINCT user_id FROM cps_order_claim WHERE platform=? AND order_no=? AND user_id<>? AND (status='BOUND' OR (status IN ('PENDING','CONFLICT') AND expires_at>?)) FOR UPDATE",Long.class,platform.name(),orderNo,userId,AttributionData.timestamp(now));
            if(!others.isEmpty()) {
                recordClaim(userId,platform,orderNo,"CONFLICT","CLAIM_CONFLICT",now,now.plus(window));
                jdbc.update("UPDATE cps_order_claim SET status='CONFLICT',reason='CLAIM_CONFLICT',updated_at=? WHERE platform=? AND order_no=? AND status IN ('BOUND','PENDING','CONFLICT')",AttributionData.timestamp(now),platform.name(),orderNo);
                for(OrderRow row:rows) conflict(row,initialCurrent(row),"CLAIM_CONFLICT",userId,now);
                return result("MANUAL_REVIEW","多人提交了同一订单，已转人工核实",orderNo,ids);
            }
            boolean allMine=true;
            for(OrderRow row:rows) {
                Decision old=initialCurrent(row);
                if(old!=null && old.priority>=PLATFORM)
                    return rejectClaim(userId,platform,orderNo,"MANUAL_REVIEW","订单正在人工处理",now,window);
                if(old!=null && old.value.userId()!=null && !Objects.equals(old.value.userId(),userId) && old.priority>=CLAIM)
                    return rejectClaim(userId,platform,orderNo,"BOUND_OTHER","订单已绑定其他用户",now,window);
                if(old!=null && old.value.userId()!=null && !Objects.equals(old.value.userId(),userId) && posted(row.id)) {
                    review(row.id,userId,"POSTED_REASSIGNMENT",userId,"LATE_CLAIM",now);
                    return rejectClaim(userId,platform,orderNo,"MANUAL_REVIEW","订单已入账，需人工账务复核",now,window);
                }
                if(old==null || !Objects.equals(old.value.userId(),userId)) allMine=false;
            }
            if(allMine) {
                recordClaim(userId,platform,orderNo,"BOUND","ALREADY_BOUND",now,now.plus(window));
                for(OrderRow row:rows) accept(row,initialCurrent(row),new AttributionDecision(userId,AttributionMethod.CLAIM,AttributionConfidence.MEDIUM,userId),CLAIM,"CLAIM_CONFIRMED",now,false);
                return result("ALREADY_BOUND","该订单已绑定您，无需重复提交",orderNo,ids);
            }
            recordClaim(userId,platform,orderNo,"BOUND","BOUND",now,now.plus(window));
            for(OrderRow row:rows) accept(row,initialCurrent(row),new AttributionDecision(userId,AttributionMethod.CLAIM,AttributionConfidence.MEDIUM,userId),CLAIM,"LATE_CLAIM",now,true);
            return result("BOUND","订单已绑定，返利处理中",orderNo,ids);
        });
    }

    public AttributionDecision manualAssign(Long orderLineId,Long targetUserId,Long operatorId,String reason) {
        AttributionData.requireUser(jdbc,targetUserId); AttributionData.text(reason,500,"审核原因");
        if(operatorId==null || operatorId<=0) throw new ServiceException("缺少后台操作人");
        boolean[] needsLedger={false};
        AttributionDecision answer=transactions.execute(tx -> {
            OrderRow row=lockOrder(orderLineId);
            if(pids.typeOf(row.line.platform(),row.line.pid())==PidType.BLOCKED) throw new ServiceException("屏蔽推广位不可认领");
            Decision old=initialCurrent(row);
            Instant now=AttributionData.now(jdbc);
            if(posted(row.id) && (old==null || !Objects.equals(old.value.userId(),targetUserId))) {
                review(row.id,targetUserId,"POSTED_REASSIGNMENT",operatorId,reason,now);
                needsLedger[0]=true;
                return old==null?new AttributionDecision(row.buyer,AttributionMethod.MANUAL,AttributionConfidence.HIGH,operatorId):old.value;
            }
            if(invalid(row)) throw new ServiceException("失效订单不可重新绑定");
            AttributionDecision next=new AttributionDecision(targetUserId,AttributionMethod.MANUAL,AttributionConfidence.HIGH,operatorId);
            Decision accepted=append(row,old,next,MANUAL,"MANUAL_ASSIGN",now);
            jdbc.update("UPDATE cps_attribution_review SET status='RESOLVED',resolved_at=? WHERE order_line_id=? AND status='PENDING'",AttributionData.timestamp(now),row.id);
            jdbc.update("INSERT INTO cps_attribution_review(order_line_id,proposed_user_id,reason,operator_id,status,detail,created_at,resolved_at) VALUES (?,?,'MANUAL_ASSIGN',?,'RESOLVED',?,?,?)",row.id,targetUserId,operatorId,reason,AttributionData.timestamp(now),AttributionData.timestamp(now));
            markClaimed(row.id,now);
            if(old==null || !Objects.equals(old.value.userId(),targetUserId)) enqueue(row.id,accepted.id,now);
            return accepted.value;
        });
        // The audit above must survive this user-facing rejection.
        if(needsLedger[0]) throw new ServiceException("该订单已经入账，已记录人工账务复核；完成冲正前保持原归属");
        return answer;
    }

    public List<UnclaimedOrder> unclaimed(Platform platform,String status,int limit) {
        int size=bounded(limit);
        if(status!=null && !List.of("OPEN","CLAIMED","PLATFORM","MANUAL_REVIEW","BLOCKED").contains(status)) throw new ServiceException("无主池状态不正确");
        String sql="SELECT u.*,o.platform_type,o.order_id,o.sub_order_no FROM cps_unclaimed_order u JOIN sys_order_commission o ON o.id=u.order_line_id WHERE 1=1";
        List<Object> args=new ArrayList<>();
        if(platform!=null) {sql+=" AND UPPER(o.platform_type)=?";args.add(platform.name());}
        if(status!=null) {sql+=" AND u.status=?";args.add(status);}
        sql+=" ORDER BY u.first_seen_at,u.order_line_id LIMIT ?";args.add(size);
        return jdbc.query(sql,(r,n)->new UnclaimedOrder(r.getLong("order_line_id"),Platform.valueOf(r.getString("platform_type").toUpperCase(Locale.ROOT)),AttributionData.mask(r.getString("order_id")),AttributionData.mask(r.getString("sub_order_no")),r.getString("status"),r.getString("reason"),r.getTimestamp("expires_at").toInstant()),args.toArray());
    }

    public int expireUnclaimed(int limit) {
        List<Long> ids=jdbc.queryForList("SELECT order_line_id FROM cps_unclaimed_order WHERE status='OPEN' AND expires_at<=? ORDER BY order_line_id LIMIT ?",Long.class,AttributionData.timestamp(AttributionData.now(jdbc)),bounded(limit));
        int changed=0;
        for(Long id:ids) {
            Boolean done=transactions.execute(tx -> {
                OrderRow row=lockOrder(id);
                List<String> states=jdbc.queryForList("SELECT status FROM cps_unclaimed_order WHERE order_line_id=? AND expires_at<=? FOR UPDATE",String.class,id,AttributionData.timestamp(AttributionData.now(jdbc)));
                if(states.isEmpty() || !"OPEN".equals(states.get(0))) return false;
                Decision old=initialCurrent(row);
                if(old!=null && old.value.userId()!=null) return false;
                Instant now=AttributionData.now(jdbc);
                append(row,old,new AttributionDecision(null,AttributionMethod.UNATTRIBUTED,AttributionConfidence.UNKNOWN,null),PLATFORM,"UNCLAIMED_EXPIRED",now);
                jdbc.update("UPDATE cps_unclaimed_order SET status='PLATFORM',reason='UNCLAIMED_EXPIRED',resolved_at=? WHERE order_line_id=?",AttributionData.timestamp(now),id);
                return true;
            });
            if(Boolean.TRUE.equals(done)) changed++;
        }
        return changed;
    }

    /** Durable retries use the same key; an unavailable settlement implementation is never success. */
    public int retrySettlement(int limit) {
        List<Map<String,Object>> jobs=jdbc.queryForList("SELECT id,order_line_id FROM cps_attribution_settlement_outbox WHERE status='PENDING' ORDER BY id LIMIT ?",bounded(limit));
        int sent=0;
        for(Map<String,Object> job:jobs) {
            long jobId=((Number)job.get("id")).longValue(), orderId=((Number)job.get("order_line_id")).longValue();
            try {
                Boolean success=transactions.execute(tx -> {
                    OrderRow row=lockOrder(orderId);
                    Map<String,Object> outbox=AttributionData.first(jdbc.query("SELECT attribution_id,event_key,created_at FROM cps_attribution_settlement_outbox WHERE id=? AND status='PENDING' FOR UPDATE",(r,n)->Map.of("attribution_id",r.getLong("attribution_id"),"event_key",r.getString("event_key"),"created_at",r.getTimestamp("created_at")),jobId));
                    if(outbox==null) return false;
                    Decision current=current(orderId);
                    if(current==null || current.value.userId()==null || current.id!=((Number)outbox.get("attribution_id")).longValue()) {
                        jdbc.update("UPDATE cps_attribution_settlement_outbox SET status='SUPERSEDED',completed_at=? WHERE id=?",AttributionData.timestamp(AttributionData.now(jdbc)),jobId); return false;
                    }
                    if(!jdbc.queryForList("SELECT id FROM cps_attribution_review WHERE order_line_id=? AND status='PENDING' LIMIT 1 FOR SHARE",Long.class,orderId).isEmpty()) {
                        pendingFailure(jobId,"REVIEW_REQUIRED");return false;
                    }
                    SettlementService downstream=settlement.getIfAvailable();
                    if(downstream==null) { pendingFailure(jobId,"SETTLEMENT_UNAVAILABLE"); return false; }
                    OrderStatus state;
                    try {state=OrderStatus.valueOf(row.status);} catch(RuntimeException invalid) {pendingFailure(jobId,"ORDER_STATE_UNAVAILABLE");return false;}
                    if(state==OrderStatus.INVALID || state==OrderStatus.FULL_REFUND || state==OrderStatus.DISPUTED || state==OrderStatus.PENALIZED) {
                        pendingFailure(jobId,"ORDER_REQUIRES_REVIEW");return false;
                    }
                    Instant bindingTime=AttributionData.instant(outbox.get("created_at"));
                    // This is an attribution event, not a fabricated alliance status transition.
                    downstream.onOrderEvent(new OrderEvent((String)outbox.get("event_key"),orderId,row.line,state,state,null,null,"ATTRIBUTION",null,bindingTime));
                    jdbc.update("UPDATE cps_attribution_settlement_outbox SET status='SENT',attempts=attempts+1,last_error=NULL,completed_at=? WHERE id=?",AttributionData.timestamp(AttributionData.now(jdbc)),jobId);
                    return true;
                });
                if(Boolean.TRUE.equals(success)) sent++;
            } catch(RuntimeException failed) {
                transactions.executeWithoutResult(tx -> pendingFailure(jobId,"DELIVERY_FAILED"));
            }
        }
        return sent;
    }

    private AttributionDecision accept(OrderRow row,Decision old,AttributionDecision next,int priority,String reason,Instant now,boolean catchup) {
        if(old!=null) {
            if(old.priority>priority) return old.value;
            if(old.priority==priority && !Objects.equals(old.value.userId(),next.userId())) return conflict(row,old,"EQUAL_PRIORITY_CONFLICT",next.decidedBy(),now);
            if(old.value.userId()!=null && !Objects.equals(old.value.userId(),next.userId()) && posted(row.id)) {
                review(row.id,next.userId(),"POSTED_REASSIGNMENT",next.decidedBy(),reason,now); return old.value;
            }
        }
        Decision accepted=append(row,old,next,priority,reason,now);
        markClaimed(row.id,now);
        if(catchup && (old==null || !Objects.equals(old.value.userId(),next.userId()))) enqueue(row.id,accepted.id,now);
        return accepted.value;
    }
    private AttributionDecision unattributed(OrderRow row,Decision old,String reason,Instant now,String poolState) {
        if(old!=null && old.value.userId()!=null) {
            if("BLOCKED_PID".equals(reason)) review(row.id,null,"BLOCKED_PID",null,reason,now);
            return old.value;
        }
        Decision saved=append(row,old,new AttributionDecision(null,AttributionMethod.UNATTRIBUTED,AttributionConfidence.UNKNOWN,null),0,reason,now);
        pool(row.id,poolState,reason,now);
        return saved.value;
    }
    private AttributionDecision conflict(OrderRow row,Decision old,String reason,Long actor,Instant now) {
        review(row.id,null,reason,actor,reason,now);
        if(posted(row.id)) return old==null?new AttributionDecision(row.buyer,AttributionMethod.MANUAL,AttributionConfidence.UNKNOWN,actor):old.value;
        Decision next=append(row,old,new AttributionDecision(null,AttributionMethod.MANUAL,AttributionConfidence.UNKNOWN,actor),MANUAL,reason,now);
        return next.value;
    }
    private Decision append(OrderRow row,Decision old,AttributionDecision value,int priority,String reason,Instant now) {
        if(old!=null && old.priority==priority && old.value.method()==value.method() && Objects.equals(old.value.userId(),value.userId())) return old;
        GeneratedKeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO cps_attribution(order_line_id,user_id,method,confidence,decided_by,is_current,created_at,priority,reason,previous_id) VALUES (?,?,?,?,?,FALSE,?,?,?,?)",Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1,row.id); statement.setObject(2,value.userId()); statement.setString(3,value.method().name());
            statement.setString(4,value.confidence().name()); statement.setObject(5,value.decidedBy());
            statement.setTimestamp(6,AttributionData.timestamp(now)); statement.setInt(7,priority);statement.setString(8,reason);statement.setObject(9,old==null?null:old.id);
            return statement;
        },key);
        long id=Objects.requireNonNull(key.getKey()).longValue();
        jdbc.update("INSERT INTO cps_attribution_current(order_line_id,attribution_id) VALUES (?,?) ON DUPLICATE KEY UPDATE attribution_id=VALUES(attribution_id)",row.id,id);
        Long inviter=invitations.resolveAt(value.userId(),row.line.paidAt());
        jdbc.update("INSERT INTO cps_attribution_inviter_snapshot(attribution_id,user_id,inviter_user_id,paid_at) VALUES (?,?,?,?)",id,value.userId(),inviter,AttributionData.timestamp(row.line.paidAt()));
        jdbc.update("UPDATE sys_order_commission SET buyer_user_id=?,agent_user_id=?,referrer_user_id=?,has_referrer=? WHERE id=?",value.userId(),value.userId()==null?0L:value.userId(),inviter,inviter==null?"0":"1",row.id);
        return new Decision(id,value,priority,reason);
    }
    private Decision initialCurrent(OrderRow row) {
        Decision known=current(row.id);
        // Pre-migration ownership cannot be silently displaced by new automatic evidence.
        if(known==null && row.buyer!=null && row.buyer>0)
            return append(row,null,new AttributionDecision(row.buyer,AttributionMethod.MANUAL,AttributionConfidence.UNKNOWN,null),MANUAL,"LEGACY_OWNER",AttributionData.now(jdbc));
        return known;
    }
    private Decision current(long orderId) {
        return AttributionData.first(jdbc.query("SELECT a.* FROM cps_attribution_current c JOIN cps_attribution a ON a.id=c.attribution_id WHERE c.order_line_id=? FOR UPDATE",(r,n)->new Decision(r.getLong("id"),new AttributionDecision((Long)r.getObject("user_id"),AttributionMethod.valueOf(r.getString("method")),AttributionConfidence.valueOf(r.getString("confidence")),(Long)r.getObject("decided_by")),r.getInt("priority"),r.getString("reason")),orderId));
    }
    private OrderRow lockOrder(Long id) {
        OrderRow row=AttributionData.first(jdbc.query("SELECT * FROM sys_order_commission WHERE id=? FOR UPDATE",this::mapOrder,id));
        if(row==null) throw new ServiceException("订单不存在");
        return row;
    }
    private OrderRow mapOrder(ResultSet r,int index) throws SQLException {
        UnionOrderLine line=new UnionOrderLine(Platform.valueOf(r.getString("platform_type").toUpperCase(Locale.ROOT)),r.getString("order_id"),r.getString("sub_order_no"),r.getString("item_id"),r.getString("pid"),r.getString("attribution_token"),r.getBigDecimal("order_amount"),r.getBigDecimal("estimated_commission"),r.getBigDecimal("actual_commission"),r.getString("raw_status"),instant(r,"paid_time"),instant(r,"received_time"),instant(r,"settle_time"),null,null);
        return new OrderRow(r.getLong("id"),line,(Long)r.getObject("buyer_user_id"),r.getString("line_status"),r.getString("order_status"));
    }
    private Instant instant(ResultSet r,String column) throws SQLException {Timestamp timestamp=r.getTimestamp(column);return timestamp==null?null:timestamp.toInstant();}
    private boolean invalid(OrderRow row) {return "cancelled".equals(row.legacyStatus) || "INVALID".equals(row.status) || "FULL_REFUND".equals(row.status);}
    private boolean posted(long orderId) {
        return !jdbc.queryForList("SELECT id FROM sys_commission_flow WHERE order_commission_id=? OR related_order_id=? LIMIT 1 FOR SHARE",Long.class,orderId,Long.toString(orderId)).isEmpty() ||
            !jdbc.queryForList("SELECT id FROM cps_settle_item WHERE order_line_id=? AND status IN ('CREDITED','PARTIALLY_RECOVERED','FULLY_RECOVERED') LIMIT 1 FOR SHARE",Long.class,orderId).isEmpty();
    }
    private boolean platformOwned(long orderId) {return !jdbc.queryForList("SELECT order_line_id FROM cps_unclaimed_order WHERE order_line_id=? AND status='PLATFORM' FOR UPDATE",Long.class,orderId).isEmpty();}
    /** An expiry deadline is enforced by every binding path, not by scheduler timing. */
    private Decision expireIfDue(OrderRow row,Decision current,Instant now) {
        if(current!=null && (current.value.userId()!=null || current.priority>=PLATFORM)) return current;
        if(jdbc.queryForList("SELECT order_line_id FROM cps_unclaimed_order WHERE order_line_id=? AND status='OPEN' AND expires_at<=? FOR UPDATE",Long.class,row.id,AttributionData.timestamp(now)).isEmpty()) return current;
        Decision expired=append(row,current,new AttributionDecision(null,AttributionMethod.UNATTRIBUTED,AttributionConfidence.UNKNOWN,null),PLATFORM,"UNCLAIMED_EXPIRED",now);
        jdbc.update("UPDATE cps_unclaimed_order SET status='PLATFORM',reason='UNCLAIMED_EXPIRED',resolved_at=? WHERE order_line_id=?",AttributionData.timestamp(now),row.id);
        return expired;
    }
    private List<Long> claimants(Platform platform,String orderNo,Instant paidAt,Instant now) {
        if(paidAt==null) return List.of();
        // Pending claims cannot bypass the age limit merely because ingestion was late.
        return jdbc.queryForList("SELECT DISTINCT user_id FROM cps_order_claim WHERE platform=? AND order_no=? AND (status='BOUND' OR (status IN ('PENDING','CONFLICT') AND expires_at>?)) AND ?>=TIMESTAMPADD(MICROSECOND,-TIMESTAMPDIFF(MICROSECOND,created_at,expires_at),created_at) AND ?<=expires_at FOR UPDATE",Long.class,platform.name(),orderNo,AttributionData.timestamp(now),AttributionData.timestamp(paidAt),AttributionData.timestamp(paidAt));
    }
    private void pool(long orderId,String state,String reason,Instant now) {
        jdbc.update("INSERT INTO cps_unclaimed_order(order_line_id,status,reason,first_seen_at,expires_at) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE status=IF(status='PLATFORM',status,VALUES(status)),reason=IF(status='PLATFORM',reason,VALUES(reason))",
            orderId,state,reason,AttributionData.timestamp(now),AttributionData.timestamp(now.plus(settings.unclaimedDuration())));
    }
    private void markClaimed(long orderId,Instant now) {jdbc.update("UPDATE cps_unclaimed_order SET status='CLAIMED',resolved_at=? WHERE order_line_id=?",AttributionData.timestamp(now),orderId);}
    private void review(long orderId,Long target,String reason,Long actor,String detail,Instant now) {
        // Called with the order lock, so a retry cannot create another open copy.
        if(jdbc.queryForList("SELECT id FROM cps_attribution_review WHERE order_line_id=? AND reason=? AND status='PENDING' AND (proposed_user_id<=>?) LIMIT 1 FOR SHARE",Long.class,orderId,reason,target).isEmpty())
            jdbc.update("INSERT INTO cps_attribution_review(order_line_id,proposed_user_id,reason,operator_id,detail,created_at) VALUES (?,?,?,?,?,?)",orderId,target,reason,actor,detail,AttributionData.timestamp(now));
        pool(orderId,"MANUAL_REVIEW",reason,now);
    }
    private void enqueue(long orderId,long attributionId,Instant now) {
        jdbc.update("INSERT IGNORE INTO cps_attribution_settlement_outbox(order_line_id,attribution_id,event_key,created_at) VALUES (?,?,?,?)",orderId,attributionId,"attribution:"+orderId+":"+attributionId,AttributionData.timestamp(now));
    }
    private void pendingFailure(long id,String reason) {jdbc.update("UPDATE cps_attribution_settlement_outbox SET attempts=attempts+1,last_error=? WHERE id=? AND status='PENDING'",reason,id);}
    private void recordClaim(Long user,Platform platform,String orderNo,String status,String reason,Instant now,Instant expiry) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM cps_order_claim WHERE platform=? AND order_no=? AND user_id=? ORDER BY id",Long.class,platform.name(),orderNo,user);
        if(ids.isEmpty()) jdbc.update("INSERT INTO cps_order_claim(user_id,platform,order_no,status,created_at,expires_at,updated_at,reason) VALUES (?,?,?,?,?,?,?,?)",user,platform.name(),orderNo,status,AttributionData.timestamp(now),AttributionData.timestamp(expiry),AttributionData.timestamp(now),reason);
        else if(!"REJECTED".equals(status)) jdbc.update("UPDATE cps_order_claim SET status=?,reason=?,updated_at=? WHERE id=?",status,reason,AttributionData.timestamp(now),ids.get(0));
    }
    private ClaimResult rejectClaim(Long user,Platform platform,String orderNo,String code,String message,Instant now,Duration duration) {
        recordClaim(user,platform,orderNo,"REJECTED",code,now,now.plus(duration));
        return result(code,message,orderNo,List.of());
    }
    private ClaimResult result(String code,String message,String orderNo,List<Long> ids) {return new ClaimResult(code,message,AttributionData.mask(orderNo),ids);}
    private int bounded(int limit) {if(limit<=0 || limit>500)throw new ServiceException("每批处理数量必须为1至500");return limit;}
}
