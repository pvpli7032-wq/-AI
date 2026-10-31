package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.cps.attribution.AttributionService;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.settlement.SettlementService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.UnionOrderLine;

/** Explicit outbox dispatcher. Downstream calls must be idempotent on redelivery. */
public final class OrderEventDispatcher
{
    private final OrderIngestionService inbox;
    private final AttributionService attribution;
    private final SettlementService settlement;

    public OrderEventDispatcher(OrderIngestionService inbox, AttributionService attribution, SettlementService settlement)
    {
        this.inbox = java.util.Objects.requireNonNull(inbox);
        this.attribution = java.util.Objects.requireNonNull(attribution);
        this.settlement = java.util.Objects.requireNonNull(settlement);
    }

    /** Claim one event: failure remains retryable and never acknowledges an incomplete stage. */
    public boolean dispatchNext(Duration lease)
    {
        var claims = inbox.claimPending(1, lease);
        if (claims.isEmpty()) return false;
        var claim = claims.get(0);
        try
        {
            Map<String, Object> row = claim.event();
            UnionOrderLine line = decode(String.valueOf(row.get("line_snapshot")));
            Long orderId = ((Number) row.get("order_line_id")).longValue();
            OrderStatus from = status(row.get("from_status"));
            if (!flag(row.get("attribution_done")))
            {
                if (from == null && attribution.decide(orderId, line) == null)
                    throw new IllegalStateException("归因服务未返回决策");
                if (!inbox.markAttributionDone(claim.id(), claim.token()))
                    throw new IllegalStateException("归因完成状态保存失败或租约已过期");
            }
            if (!flag(row.get("settlement_done")))
            {
                Instant occurred = Instant.ofEpochSecond(((Number)row.get("occurred_second")).longValue(),
                    ((Number)row.get("occurred_nano")).longValue());
                Long rawId = row.get("raw_id") == null ? null : ((Number)row.get("raw_id")).longValue();
                settlement.onOrderEvent(new OrderEvent((String)row.get("event_key"), orderId, line, from,
                    status(row.get("to_status")), status(row.get("previous_status")), null,
                    "ALLIANCE", rawId, occurred));
                if (!inbox.markSettlementDone(claim.id(), claim.token()))
                    throw new IllegalStateException("结算投递状态保存失败或租约已过期");
            }
            if (!inbox.acknowledge(claim.id(), claim.token()))
                throw new IllegalStateException("订单事件确认失败或租约已过期");
            return true;
        }
        catch (RuntimeException failure)
        {
            try { inbox.release(claim.id(), claim.token()); }
            catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
            throw failure;
        }
    }

    private static UnionOrderLine decode(String snapshot)
    {
        JSONObject data = JSON.parseObject(snapshot);
        if (data == null) throw new IllegalArgumentException("订单快照无效");
        return new UnionOrderLine(Platform.valueOf(data.getString("platform").toUpperCase(Locale.ROOT)),
            data.getString("orderNo"), data.getString("subOrderNo"), data.getString("itemId"),
            data.getString("pid"), data.getString("attributionToken"), money(data, "paidAmount"),
            money(data, "estimatedCommission"), money(data, "actualCommission"), data.getString("rawStatus"),
            instant(data, "paidAt"), instant(data, "receivedAt"), instant(data, "settledAt"));
    }
    private static BigDecimal money(JSONObject value, String key) { return value.getBigDecimal(key); }
    private static Instant instant(JSONObject value, String key)
    {
        String text = value.getString(key);
        return text == null ? null : Instant.parse(text);
    }
    private static OrderStatus status(Object value) { return value == null ? null : OrderStatus.valueOf(value.toString()); }
    private static boolean flag(Object value)
    {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0;
    }
}
