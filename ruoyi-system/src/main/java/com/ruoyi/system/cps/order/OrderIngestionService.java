package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Objects;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderEventMapper;
import com.ruoyi.system.cps.mapper.OrderIngestMapper;
import com.ruoyi.system.cps.mapper.OrderRawMapper;
import com.ruoyi.union.adapter.UnionOrderLine;
import com.ruoyi.union.adapter.Platform;

/** Explicit ingress boundary. Callers provide actual raw JSON and real alliance status time. */
public class OrderIngestionService
{
    public enum Outcome { APPLIED, DUPLICATE, REJECTED }
    public record Result(long rawId, Long orderLineId, String eventKey, Outcome outcome) {}
    /** Row keys are SQL column names; line_snapshot is JSON for reconstructing UnionOrderLine. */
    public record Claim(long id, String token, Map<String,Object> event) {}
    private final OrderRawMapper raw;
    private final OrderIngestMapper orders;
    private final OrderEventMapper events;
    private final OrderStatusMapper statuses;
    private final OrderTransitionValidator transitions;
    private final TransactionTemplate independent;

    public OrderIngestionService(PlatformTransactionManager tx, OrderRawMapper raw, OrderIngestMapper orders,
            OrderEventMapper events, OrderStatusMapper statuses, OrderTransitionValidator transitions)
    {
        this.raw = raw; this.orders = orders; this.events = events; this.statuses = statuses; this.transitions = transitions;
        independent = new TransactionTemplate(tx);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Result ingest(IncomingOrder input)
    {
        if (input == null || input.rawJson() == null || input.fetchedAt() == null
            || input.line() == null || input.line().platform() == null)
            throw new IllegalArgumentException("必须提供联盟原始响应、平台和抓取时间");
        long rawId = retainRaw(input.line().platform(), input.rawJson(), input.fetchedAt());
        independent.executeWithoutResult(status -> {
            if (raw.appendInput(Map.of("rawId", rawId, "input", JSON.toJSONString(input))) != 1)
                throw new IllegalStateException("订单重放输入保存失败");
        });
        try
        {
            return independent.execute(status -> process(rawId, input));
        }
        catch (RuntimeException failure)
        {
            // Raw evidence was committed independently. Do not copy SQL values or secrets to the error reason.
            recordException(rawId, null, null, "入站处理失败，保留原文待重放");
            return new Result(rawId, null, null, Outcome.REJECTED);
        }
    }

    /** Reprocess immutable original evidence; never accepts replacement time/status/money from an operator. */
    public Result replay(long rawId)
    {
        if (rawId <= 0) throw new IllegalArgumentException("原始记录ID无效");
        String snapshot = raw.selectInput(rawId);
        if (snapshot == null) throw new IllegalArgumentException("该原始记录没有可重放的订单行，需重新采集解析");
        return ingest(JSON.parseObject(snapshot, IncomingOrder.class));
    }

    /** Retain actual provider text before deferring an unsupported case for retry. */
    public long retainRaw(Platform platform, String text, Instant fetchedAt)
    {
        if (platform == null || text == null || fetchedAt == null)
            throw new IllegalArgumentException("必须提供联盟原始响应、平台和抓取时间");
        Map<String,Object> data = new HashMap<>();
        data.put("raw", text);
        data.put("platform", platform.name());
        data.put("fetchedAt", timestamp(fetchedAt));
        data.put("hash", sha256(text));
        independent.executeWithoutResult(status -> {
            if (raw.append(data) != 1 || data.get("id") == null)
                throw new IllegalStateException("联盟原始响应保存失败");
        });
        return ((Number) data.get("id")).longValue();
    }

    /** Source anomalies are durable, not just log messages. */
    public void recordSourceException(long rawId, String reason) {
        if (rawId <= 0 || reason == null || reason.length() > 500) throw new IllegalArgumentException("来源异常无效");
        recordException(rawId, null, null, reason);
    }

    private Result process(long rawId, IncomingOrder input)
    {
        if (!raw.isValidJson(input.rawJson())) return rejected(rawId, null, null, "联盟响应不是有效JSON");
        UnionOrderLine line = input.line();
        if (line == null || line.platform() == null || empty(line.orderNo()) || line.orderNo().length() > 100
            || line.subOrderNo() == null || line.subOrderNo().length() > 100 || empty(line.rawStatus())
            || line.rawStatus().length() > 128 || input.allianceStatusTime() == null)
            return rejected(rawId, null, null, "订单标识或联盟状态时间缺失或无效");
        if (tooLong(line.itemId(),100) || tooLong(line.pid(),128) || tooLong(line.attributionToken(),255)
            || line.paidAmount() == null || !moneyFits(line.paidAmount(),10,2) || !moneyFits(line.estimatedCommission(),14,4)
            || !moneyFits(line.actualCommission(),14,4))
            return rejected(rawId, null, null, "订单字段或金额精度超出允许范围");

        String eventKey = OrderEvent.generateEventKey(line.platform(), line.orderNo(), line.subOrderNo(),
            line.rawStatus(), input.allianceStatusTime());
        Map<String,Object> values = new HashMap<>();
        values.put("platform", line.platform().name().toLowerCase(Locale.ROOT));
        values.put("orderNo", line.orderNo()); values.put("subOrderNo", line.subOrderNo());
        values.put("itemId", line.itemId()); values.put("pid", line.pid()); values.put("token", line.attributionToken());
        values.put("paidAmount", line.paidAmount()); values.put("estimated", line.estimatedCommission());
        values.put("actual", line.actualCommission()); values.put("paidAt", timestamp(line.paidAt()));
        values.put("receivedAt", timestamp(line.receivedAt())); values.put("settledAt", timestamp(line.settledAt()));
        values.put("paidAtText", instantText(line.paidAt())); values.put("receivedAtText", instantText(line.receivedAt()));
        values.put("settledAtText", instantText(line.settledAt()));
        values.put("paidSecond", line.paidAt() == null ? null : line.paidAt().getEpochSecond());
        values.put("paidNano", line.paidAt() == null ? null : line.paidAt().getNano());
        values.put("settledSecond", line.settledAt() == null ? null : line.settledAt().getEpochSecond());
        values.put("settledNano", line.settledAt() == null ? null : line.settledAt().getNano());
        orders.ensureLine(values);
        Map<String,Object> row = orders.lockLine(values);
        long orderId = ((Number) row.get("id")).longValue();
        if (events.countByKey(eventKey) != 0)
        {
            String snapshot = events.selectSnapshot(eventKey);
            if (snapshot == null || !sameLine(snapshot, line))
                return rejected(rawId, orderId, eventKey, "相同事件键对应不同订单数据，需人工核对");
            return new Result(rawId, orderId, eventKey, Outcome.DUPLICATE);
        }
        OrderStatus current = status(row.get("line_status"));
        OrderStatus previous = status(row.get("cps_dispute_previous"));
        OrderStatus target;
        try { target = input.restorePrevious() ? previous : input.resolvedStatus() != null
            ? input.resolvedStatus() : statuses.map(line.platform(), line.rawStatus()); }
        catch (IllegalArgumentException unknown) { return rejected(rawId, orderId, eventKey, "未知或未确认的联盟状态"); }
        if (target == null || (input.restorePrevious() && current != OrderStatus.DISPUTED))
            return rejected(rawId, orderId, eventKey, "没有可恢复的维权前状态");
        Number lastSecond = (Number) row.get("cps_status_second");
        if (lastSecond != null)
        {
            Number nanos = (Number) row.get("cps_status_nano");
            if (nanos == null || !input.allianceStatusTime().isAfter(Instant.ofEpochSecond(lastSecond.longValue(), nanos.intValue())))
                return rejected(rawId, orderId, eventKey, "联盟事件时间早于或等于已处理事件");
        }
        // First observation is a snapshot, not a transition from a fabricated PAID state.
        // Only existing orders go through the shared transition table; no history/times are inferred.
        OrderTransitionValidator.Result decision = current == null
            ? new OrderTransitionValidator.Result(OrderTransitionValidator.Outcome.INITIAL, "")
            : transitions.evaluate(current, target, previous);
        if (decision.outcome() == OrderTransitionValidator.Outcome.ILLEGAL)
            return rejected(rawId, orderId, eventKey, decision.reason());
        boolean commissionChanged = line.platform() == Platform.JD && current == target
            && line.actualCommission() != null && (row.get("actual_commission") == null
            || line.actualCommission().compareTo(new BigDecimal(row.get("actual_commission").toString())) != 0);
        if (decision.outcome() == OrderTransitionValidator.Outcome.DUPLICATE && !commissionChanged)
            return rejected(rawId, orderId, eventKey, "同状态新事件可能包含金额变化，需人工核对后重放");
        // A commission-only event does not change state, and never fabricates an intermediate transition.
        values.put("source", commissionChanged ? "ALLIANCE_COMMISSION" : input.source() == null ? "ALLIANCE" : input.source());
        OrderStatus eventPrevious = target == OrderStatus.DISPUTED && current != OrderStatus.DISPUTED
            ? current : current == OrderStatus.DISPUTED ? previous : null;
        values.put("id", orderId); values.put("orderLineId", orderId); values.put("rawId", rawId);
        values.put("status", target.name()); values.put("toStatus", target.name());
        values.put("fromStatus", current == null ? null : current.name());
        values.put("previous", eventPrevious == null ? null : eventPrevious.name());
        values.put("rawStatus", line.rawStatus()); values.put("eventKey", eventKey);
        values.put("seconds", input.allianceStatusTime().getEpochSecond()); values.put("nanos", input.allianceStatusTime().getNano());
        values.put("occurredAt", timestamp(input.allianceStatusTime()));
        Instant paid = line.paidAt() == null ? instantFromRow(row, "cps_paid_second", "cps_paid_nano") : line.paidAt();
        Instant settled = line.settledAt() == null ? instantFromRow(row, "cps_settled_second", "cps_settled_nano") : line.settledAt();
        Boolean suspicious = null;
        if (paid != null && settled != null && !settled.isBefore(paid))
            suspicious = Duration.between(paid, settled).compareTo(Duration.ofHours(24)) < 0;
        values.put("suspicious", suspicious);
        BigDecimal recovered = null, refundBasis = null;
        if (target == OrderStatus.FULL_REFUND || target == OrderStatus.PARTIAL_REFUND) {
            String firstRefund = events.selectFirstRefundSnapshot(orderId);
            // The live row may already contain a changed dispute/refund actualFee. A refund-feed
            // restoration also copies that row and is not new evidence of the original commission.
            // Freeze even a missing basis at the first refund; never reinterpret an earlier recovery.
            if (firstRefund != null) {
                refundBasis = JSON.parseObject(firstRefund).getBigDecimal("refundBasisAmount");
            } else if (current != null) {
                String original = events.selectLatestOrderCommissionSnapshot(orderId);
                if (original != null) refundBasis = JSON.parseObject(original).getBigDecimal("actualCommission");
            }
            if (!moneyFits(refundBasis, 14, 4)) {
                refundBasis = null;
                rejected(rawId, orderId, eventKey, "原订单实际联盟佣金证据无效，退款基数留空，线11暂停转人工");
            }
            if (target == OrderStatus.FULL_REFUND) {
                recovered = refundBasis;
                if (recovered == null)
                    rejected(rawId, orderId, eventKey, "全额退款缺原订单实际联盟佣金，recoveredAmount留空，线11暂停转人工");
            } else {
                // No currently confirmed DTK field proves cumulative affiliate commission recovery.
                // Neither refund_fee nor tk_commission_fee_refund_pub is such evidence.
                rejected(rawId, orderId, eventKey, "部分退款缺可证实的累计联盟佣金追回额，recoveredAmount留空，线11暂停转人工");
            }
        }
        values.put("recovered", recovered); values.put("refundBasis", refundBasis);
        if (orders.updateStatus(values) != 1 || events.append(values) != 1 || events.enqueue(values) != 1)
            throw new IllegalStateException("订单事件保存失败");
        // Stored dispute state is meaningful only while currently disputed.
        if (target != OrderStatus.DISPUTED && eventPrevious != null)
        {
            values.put("previous", null);
            if (orders.updateStatus(values) != 1) throw new IllegalStateException("订单状态保存失败");
        }
        return new Result(rawId, orderId, eventKey, Outcome.APPLIED);
    }

    private Result rejected(long rawId, Long orderId, String eventKey, String reason)
    {
        Map<String,Object> error = new HashMap<>();
        error.put("rawId", rawId); error.put("orderLineId", orderId); error.put("eventKey", eventKey); error.put("reason", reason);
        orders.appendException(error);
        return new Result(rawId, orderId, eventKey, Outcome.REJECTED);
    }

    private void recordException(long rawId, Long orderId, String eventKey, String reason)
    {
        independent.executeWithoutResult(status -> rejected(rawId, orderId, eventKey, reason));
    }

    /** Claim is durable; abandoned claims become eligible after the lease. */
    public List<Claim> claimPending(int limit, Duration lease)
    {
        if (limit < 1 || limit > 100 || lease == null || lease.getSeconds() < 1 || lease.getSeconds() > 3600)
            throw new IllegalArgumentException("出箱领取数量或租约无效");
        return independent.execute(status -> {
            List<Claim> result = new ArrayList<>();
            for (Map<String,Object> row : events.lockPending(limit))
            {
                long id = ((Number)row.get("id")).longValue(); String token = UUID.randomUUID().toString();
                Map<String,Object> params = Map.of("id", id, "token", token, "seconds", lease.getSeconds());
                if (events.claim(params) != 1) throw new IllegalStateException("出箱领取失败");
                result.add(new Claim(id, token, row));
            }
            return result;
        });
    }
    public boolean markAttributionDone(long id, String token) { return markStage(id, token, true); }
    public boolean markSettlementDone(long id, String token) { return markStage(id, token, false); }
    private boolean markStage(long id, String token, boolean attribution)
    {
        if (id <= 0 || token == null || token.isBlank()) throw new IllegalArgumentException("出箱领取凭证无效");
        return independent.execute(status -> (attribution ? events.markAttributionDone(Map.of("id", id, "token", token))
            : events.markSettlementDone(Map.of("id", id, "token", token))) == 1);
    }
    public boolean acknowledge(long id, String token) { return changeClaim(id, token, true); }
    public boolean release(long id, String token) { return changeClaim(id, token, false); }
    private boolean changeClaim(long id, String token, boolean success)
    {
        if (id <= 0 || token == null || token.isBlank()) throw new IllegalArgumentException("出箱领取凭证无效");
        return independent.execute(status -> (success ? events.acknowledge(Map.of("id", id, "token", token))
            : events.release(Map.of("id", id, "token", token))) == 1);
    }
    private static boolean empty(String s) { return s == null || s.isBlank(); }
    private static boolean sameLine(String snapshot, UnionOrderLine line)
    {
        JSONObject data = JSON.parseObject(snapshot);
        if (data == null) return false;
        return Objects.equals(data.getString("platform"), line.platform().name().toLowerCase(Locale.ROOT))
            && Objects.equals(data.getString("orderNo"), line.orderNo())
            && Objects.equals(data.getString("subOrderNo"), line.subOrderNo())
            && Objects.equals(data.getString("itemId"), line.itemId())
            && Objects.equals(data.getString("pid"), line.pid())
            && Objects.equals(data.getString("attributionToken"), line.attributionToken())
            && Objects.equals(data.getString("rawStatus"), line.rawStatus())
            && equalMoney(data.getBigDecimal("paidAmount"), line.paidAmount())
            && equalMoney(data.getBigDecimal("estimatedCommission"), line.estimatedCommission())
            && equalMoney(data.getBigDecimal("actualCommission"), line.actualCommission())
            && equalInstant(data.getString("paidAt"), line.paidAt())
            && equalInstant(data.getString("receivedAt"), line.receivedAt())
            && equalInstant(data.getString("settledAt"), line.settledAt());
    }
    private static boolean equalMoney(BigDecimal a, BigDecimal b)
    {
        return a == null || b == null ? a == b : a.compareTo(b) == 0;
    }
    private static boolean equalInstant(String a, Instant b)
    {
        return a == null || b == null ? a == null && b == null : Instant.parse(a).equals(b);
    }
    private static boolean tooLong(String s, int max) { return s != null && s.length() > max; }
    private static Instant instantFromRow(Map<String,Object> row, String secondKey, String nanoKey)
    {
        Number second = (Number) row.get(secondKey), nano = (Number) row.get(nanoKey);
        return second == null || nano == null ? null : Instant.ofEpochSecond(second.longValue(), nano.intValue());
    }
    private static String instantText(Instant value) { return value == null ? null : value.toString(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static OrderStatus status(Object value) { return value == null ? null : OrderStatus.valueOf(value.toString()); }
    private static boolean moneyFits(BigDecimal value, int precision, int scale)
    {
        if (value == null) return true;
        try { return value.signum() >= 0 && value.setScale(scale, RoundingMode.UNNECESSARY).precision() <= precision; }
        catch (ArithmeticException invalid) { return false; }
    }
    private static String sha256(String text)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
