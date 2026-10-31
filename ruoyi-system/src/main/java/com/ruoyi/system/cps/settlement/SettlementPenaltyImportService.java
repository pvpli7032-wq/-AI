package com.ruoyi.system.cps.settlement;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.rule.RewardApi;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Permission-protected MANUAL penalty statement import. The operator supplies the
 * source's cumulative recovered ALLIANCE commission and source timestamp, never a
 * product refund amount or a guessed share of a monthly aggregate. Each explicit
 * order line is applied independently; immutable input survives a row failure.
 * This does not claim integration with line 10's automatic penalty collector.
 */
@Service
public class SettlementPenaltyImportService {
    public record Line(long orderLineId, Instant allianceStatusTime,
                       BigDecimal cumulativeRecoveredCommission, String rawEvidence) {}
    public record Request(String requestKey, Platform platform, String fileName,
                          String sourceReference, String statementPeriod, List<Line> lines) {}
    public record LineResult(int lineNo, long orderLineId, String status, String resultCode) {}
    public record Result(long importId, long sourceImportId, List<LineResult> lines) {}
    private final JdbcTemplate jdbc;
    private final SettlementService settlement;
    private final TransactionTemplate transactions;

    public SettlementPenaltyImportService(JdbcTemplate jdbc, SettlementService settlement,
                                           PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.settlement=settlement;
        transactions=new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result importStatement(Request request, long operatorId) {
        validate(request,operatorId);
        // Copy the caller's list before hashing/persisting; later mutations cannot change evidence.
        Request immutable=new Request(request.requestKey(),request.platform(),request.fileName(),
            request.sourceReference(),request.statementPeriod(),List.copyOf(request.lines()));
        long id;
        try { id=Objects.requireNonNull(transactions.execute(status -> persist(immutable,operatorId))); }
        catch (org.springframework.dao.DuplicateKeyException concurrent) {
            // The loser transaction (including its source row) rolled back. Re-read the
            // winner under the same payload check; no orphan or second application.
            id=Objects.requireNonNull(transactions.execute(status -> persist(immutable,operatorId)));
        }
        return retry(id);
    }

    /** Explicit administrative retry; applied/manual rows are never replayed as fresh evidence. */
    public Result retry(long importId) {
        if (importId<=0) throw new IllegalArgumentException("Invalid penalty import ID");
        List<Long> pending=jdbc.queryForList("SELECT id FROM cps_settlement_penalty_line WHERE import_id=? "
            + "AND status IN ('PENDING','FAILED') ORDER BY id",Long.class,importId);
        for (long lineId:pending) {
            try {
                transactions.executeWithoutResult(status -> apply(lineId));
            } catch (RuntimeException failure) {
                // Do not store raw exception text: it can contain SQL, credentials or raw evidence.
                transactions.executeWithoutResult(status -> preserveFailure(lineId));
            }
        }
        return result(importId);
    }

    public Result result(long importId) {
        Long source=jdbc.queryForObject("SELECT punish_import_id FROM cps_settlement_penalty_import WHERE id=?",Long.class,importId);
        List<LineResult> lines=jdbc.query("SELECT line_no,order_line_id,status,result_code FROM cps_settlement_penalty_line "
            + "WHERE import_id=? ORDER BY line_no",(r,n)->new LineResult(r.getInt(1),r.getLong(2),r.getString(3),r.getString(4)),importId);
        return new Result(importId,source,List.copyOf(lines));
    }

    private long persist(Request request,long operator) {
        String key=DefaultSettlementService.digest(request.requestKey());
        String payload=JSON.toJSONString(request), hash=DefaultSettlementService.digest(payload);
        // Global manual-import guard is the immutable source row's request identity. The
        // connection-scoped named lock is deliberately avoided; unique INSERT handles races.
        List<Map<String,Object>> prior=jdbc.queryForList("SELECT id,payload_hash FROM cps_settlement_penalty_import WHERE import_key=? FOR UPDATE",key);
        if (!prior.isEmpty()) {
            if (!hash.equals(prior.getFirst().get("payload_hash"))) throw new IllegalArgumentException("Penalty request key payload mismatch");
            return ((Number)prior.getFirst().get("id")).longValue();
        }
        var sourceKey=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO cps_punish_import(platform,file,operator,\u0060lines\u0060) VALUES(?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,request.platform().name()); statement.setString(2,request.fileName());
            statement.setLong(3,operator); statement.setInt(4,request.lines().size()); return statement;
        },sourceKey);
        long source=Objects.requireNonNull(sourceKey.getKey()).longValue();
        var importKey=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement=connection.prepareStatement("INSERT INTO cps_settlement_penalty_import"
                + "(import_key,payload_hash,punish_import_id,platform,statement_period,source_reference,payload,operator_id) VALUES(?,?,?,?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1,key); statement.setString(2,hash); statement.setLong(3,source);
            statement.setString(4,request.platform().name()); statement.setString(5,request.statementPeriod());
            statement.setString(6,request.sourceReference()); statement.setString(7,payload); statement.setLong(8,operator); return statement;
        },importKey);
        long id=Objects.requireNonNull(importKey.getKey()).longValue();
        int index=0;
        for (Line line:request.lines()) {
            index++;
            jdbc.update("INSERT INTO cps_settlement_penalty_line(import_id,line_no,order_line_id,event_key,input_json) VALUES(?,?,?,?,?)",
                id,index,line.orderLineId(),"penalty:"+key+":"+index,JSON.toJSONString(line));
        }
        return id;
    }

    private void apply(long id) {
        Map<String,Object> row=jdbc.queryForMap("SELECT l.*,i.platform,i.punish_import_id FROM cps_settlement_penalty_line l "
            + "JOIN cps_settlement_penalty_import i ON i.id=l.import_id WHERE l.id=? FOR UPDATE",id);
        if (!List.of("PENDING","FAILED").contains(row.get("status"))) return;
        long order=((Number)row.get("order_line_id")).longValue();
        Line input=JSON.parseObject((String)row.get("input_json"),Line.class);
        List<Map<String,Object>> orders=jdbc.queryForList("SELECT * FROM sys_order_commission WHERE id=? FOR UPDATE",order);
        if (orders.isEmpty()) { mark(id,"MANUAL_REVIEW","ORDER_NOT_FOUND"); return; }
        Map<String,Object> stored=orders.getFirst();
        if (!String.valueOf(row.get("platform")).equalsIgnoreCase(String.valueOf(stored.get("platform_type")))) {
            mark(id,"MANUAL_REVIEW","PLATFORM_MISMATCH"); return;
        }
        if (input.allianceStatusTime()==null) {
            hold(row,"MISSING_SOURCE_STATUS_TIME");
            mark(id,"MANUAL_REVIEW","MISSING_SOURCE_STATUS_TIME"); return;
        }
        String eventJson=(String)row.get("event_json");
        OrderEvent event;
        if (eventJson!=null) event=JSON.parseObject(eventJson,OrderEvent.class);
        else {
            List<Map<String,Object>> positions=jdbc.queryForList("SELECT * FROM cps_settlement_position WHERE order_line_id=?",order);
            List<String> snapshots=jdbc.queryForList("SELECT payload FROM cps_reward_order_snapshot WHERE order_id=?",String.class,order);
            if (positions.isEmpty() || snapshots.isEmpty() || positions.getFirst().get("original_commission")==null) {
                hold(row,"MISSING_SETTLEMENT_COMMISSION_OR_PAYMENT_EVIDENCE");
                mark(id,"MANUAL_REVIEW","MISSING_SETTLEMENT_COMMISSION_OR_PAYMENT_EVIDENCE"); return;
            }
            Map<String,Object> position=positions.getFirst();
            RewardApi.Snapshot snapshot=JSON.parseObject(snapshots.getFirst(),RewardApi.Snapshot.class);
            BigDecimal commission=(BigDecimal)position.get("original_commission");
            var line=new UnionOrderLine(Platform.valueOf(String.valueOf(row.get("platform"))),
                String.valueOf(stored.get("order_id")),String.valueOf(stored.get("sub_order_no")),null,null,null,
                (BigDecimal)stored.get("order_amount"),commission,commission,"MANUAL_CONFIRMED_PENALTY",
                snapshot.paidAt(),null,null,input.rawEvidence(),input.allianceStatusTime());
            event=new OrderEvent((String)row.get("event_key"),order,line,
                OrderStatus.valueOf((String)position.get("order_status")),OrderStatus.PENALIZED,null,
                input.cumulativeRecoveredCommission(),"MANUAL_PENALTY_STATEMENT",null,input.allianceStatusTime());
            eventJson=JSON.toJSONString(event);
            jdbc.update("UPDATE cps_settlement_penalty_line SET event_json=? WHERE id=? AND event_json IS NULL",eventJson,id);
        }
        settlement.onOrderEvent(event);
        Integer manual=jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_manual WHERE order_line_id=? AND event_key=?",
            Integer.class,order,event.eventKey());
        Integer receipt=jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_receipt WHERE event_key=?",Integer.class,event.eventKey());
        if (manual!=null && manual>0 || receipt==null || receipt==0) {
            mark(id,"MANUAL_REVIEW","SETTLEMENT_REQUIRES_MANUAL_REVIEW");
        } else mark(id,"APPLIED","CUMULATIVE_PENALTY_APPLIED");
    }

    private void preserveFailure(long id) {
        Map<String,Object> row=jdbc.queryForMap("SELECT l.*,i.platform FROM cps_settlement_penalty_line l "
            + "JOIN cps_settlement_penalty_import i ON i.id=l.import_id WHERE l.id=? FOR UPDATE",id);
        if (!List.of("PENDING","FAILED").contains(row.get("status"))) return;
        long order=((Number)row.get("order_line_id")).longValue();
        List<String> platforms=jdbc.queryForList("SELECT platform_type FROM sys_order_commission WHERE id=? FOR UPDATE",String.class,order);
        if (platforms.size()==1 && platforms.getFirst().equalsIgnoreCase(String.valueOf(row.get("platform")))) {
            hold(row,"PENALTY_PROCESSING_FAILED_REQUIRES_MANUAL_RECONCILIATION");
            mark(id,"MANUAL_REVIEW","PENALTY_PROCESSING_FAILED_REQUIRES_MANUAL_RECONCILIATION");
        } else mark(id,"FAILED","PROCESSING_FAILED_RETRY_OR_RECONCILE");
    }

    private void hold(Map<String,Object> row,String reason) {
        // System observation time may pause a clock; it must never be substituted for
        // an unknown alliance status time or used to manufacture an OrderEvent.
        new SettlementRepository(jdbc).holdUnprocessableEvidence(
            ((Number)row.get("order_line_id")).longValue(),(String)row.get("event_key"),reason,(String)row.get("input_json"));
    }

    private void mark(long id,String status,String code) {
        jdbc.update("UPDATE cps_settlement_penalty_line SET status=?,result_code=?,processed_at=CURRENT_TIMESTAMP(3) WHERE id=?",status,code,id);
    }

    private static void validate(Request r,long operator) {
        if (r==null || operator<=0 || r.platform()==null) throw new IllegalArgumentException("Invalid penalty import actor/platform");
        text(r.requestKey(),128); text(r.fileName(),500); text(r.sourceReference(),500);
        if (r.statementPeriod()!=null) YearMonth.parse(r.statementPeriod());
        if (r.lines()==null || r.lines().isEmpty() || r.lines().size()>500) throw new IllegalArgumentException("Import requires 1-500 explicit order lines");
        var ids=new HashSet<Long>();
        for (Line line:r.lines()) {
            if (line==null || line.orderLineId()<=0 || !ids.add(line.orderLineId())) throw new IllegalArgumentException("Invalid or duplicate order line");
            text(line.rawEvidence(),65536);
            BigDecimal amount=line.cumulativeRecoveredCommission();
            if (amount!=null && (amount.signum()<0 || amount.scale()>8 || (long)amount.precision()-amount.scale()>12))
                throw new IllegalArgumentException("Invalid cumulative alliance commission");
        }
    }
    private static void text(String value,int maximum) {
        if (value==null || value.isBlank() || value.length()>maximum) throw new IllegalArgumentException("Missing or oversized source evidence");
    }
}
