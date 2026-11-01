package com.ruoyi.system.cps.settlement;

import com.alibaba.fastjson2.JSON;
import com.ruoyi.system.cps.contract.OrderEvent;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.union.adapter.Platform;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Order-evidence-backed PDD notices. The existing DTK response DTO defines
 * priceCompareStatus 0=normal, 1=price comparison. Zero commission alone is never
 * classified as price comparison. First configured text and exact source are
 * retained separately from the privacy-minimized notification channel contract.
 */
@Service
public class SettlementPddNoticeService {
    public static final String PRICE_PREFIX="cps.settlement.pdd.priceCompareNotice.";
    public static final String ZERO_PREFIX="cps.settlement.pdd.zeroCommissionNotice.";
    public record Notice(long id,String reason,String message,String deliveryStatus,
                         BigDecimal lossAmount,BigDecimal balanceAfter,Instant createdAt) {}
    private final JdbcTemplate jdbc;
    private final SettlementNotificationService notifications;
    public SettlementPddNoticeService(JdbcTemplate jdbc,SettlementNotificationService notifications) {
        this.jdbc=jdbc; this.notifications=notifications;
    }

    /** Called inside the successfully processed order's existing ledger transaction. */
    public void capture(OrderEvent event,long userId) {
        if (event.line().platform()!=Platform.PDD || event.line().actualCommission()==null
                || event.line().actualCommission().signum()!=0
                || event.toStatus()!=OrderStatus.SETTLED && event.toStatus()!=OrderStatus.INVALID) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("PDD outcome capture requires the settlement transaction");
        Long owner=jdbc.queryForObject("SELECT buyer_user_id FROM sys_order_commission WHERE id=? FOR UPDATE",
            Long.class,event.orderLineId());
        if (owner==null || userId<=0 || owner.longValue()!=userId)
            throw new IllegalArgumentException("PDD notice beneficiary must match persisted order ownership");
        boolean comparison=confirmedPriceComparison(event.line().rawResponseJson(),event.line().orderNo());
        String reason=comparison ? SettlementNotificationService.PDD_PRICE_COMPARE_ZERO :
            SettlementNotificationService.PDD_ZERO_COMMISSION;
        Integer existing=jdbc.queryForObject("SELECT COUNT(*) FROM cps_settlement_pdd_notice WHERE order_line_id=? AND reason=?",
            Integer.class,event.orderLineId(),reason);
        if (existing!=null && existing>0) return;
        String prefix=comparison ? PRICE_PREFIX : ZERO_PREFIX;
        String enabled=config(prefix+"enabled");
        if ("false".equalsIgnoreCase(enabled)) return;
        if (!"true".equalsIgnoreCase(enabled)) { configurationIssue(event); return; }
        String message=config(prefix+"message");
        if (message==null || message.isBlank() || message.length()>1000) { configurationIssue(event); return; }
        String key="pdd-zero:"+event.orderLineId()+":"+reason;
        String evidence=event.line().rawResponseJson()==null ? "" : event.line().rawResponseJson();
        jdbc.update("INSERT INTO cps_settlement_pdd_notice(order_line_id,user_id,reason,event_key,business_key_hash,message,source_hash,source_evidence) "
            + "VALUES(?,?,?,?,?,?,?,?)",event.orderLineId(),userId,reason,event.eventKey(),DefaultSettlementService.digest(key),message,
            DefaultSettlementService.digest(evidence),evidence);
        List<BigDecimal> balances=jdbc.queryForList("SELECT balance FROM sys_agent_commission_account WHERE user_id=? FOR UPDATE",BigDecimal.class,userId);
        // This outcome explains a final zero commission, not a second monetary deduction.
        notifications.enqueue(key,userId,event.orderLineId(),reason,BigDecimal.ZERO,
            balances.isEmpty() ? BigDecimal.ZERO : balances.getFirst());
    }

    /** Authenticated caller's own display projection; no raw source, event key or external order ID. */
    @Transactional(readOnly=true)
    public List<Notice> list(long userId,int limit) {
        if (userId<=0 || limit<1 || limit>100) throw new IllegalArgumentException("Authenticated user and bounded notice limit required");
        return jdbc.query("SELECT p.id,p.reason,p.message,n.status,n.loss_amount,n.balance_after,p.created_at "
            + "FROM cps_settlement_pdd_notice p JOIN cps_settlement_notification n ON n.business_key_hash=p.business_key_hash "
            + "WHERE p.user_id=? AND n.user_id=? ORDER BY p.id DESC LIMIT ?",
            (r,n)->new Notice(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),
                r.getBigDecimal(5),r.getBigDecimal(6),r.getTimestamp(7).toInstant()),userId,userId,limit);
    }

    private String config(String key) {
        List<String> values=jdbc.queryForList("SELECT config_value FROM sys_config WHERE config_key=?",String.class,key);
        return values.size()==1 ? values.getFirst() : null;
    }

    private void configurationIssue(OrderEvent event) {
        // Notification display settings never veto authoritative financial corrections.
        // This is an operational record, not a money hold or a fabricated default message.
        new SettlementRepository(jdbc).manual(event.orderLineId(),
            "pdd-notice-config:"+DefaultSettlementService.digest(event.eventKey()),
            "PDD_NOTICE_CONFIGURATION_INVALID");
    }

    static boolean confirmedPriceComparison(String raw,String orderNo) {
        if (raw==null || raw.isBlank() || raw.length()>1_000_000 || orderNo==null) return false;
        try {
            var matches=new ArrayList<Map<?,?>>();
            walk(JSON.parse(raw),orderNo,matches,0,new int[]{0});
            if (matches.size()!=1) return false;
            Map<?,?> row=matches.getFirst();
            Object camel=row.get("priceCompareStatus"), snake=row.get("price_compare_status");
            if (camel!=null && snake!=null && !numericEqual(camel,snake)) return false;
            Object status=camel!=null ? camel : snake;
            return integer(status)==1;
        } catch (RuntimeException malformed) {
            return false; // Unknown/malformed evidence gets only the generic zero-commission notice.
        }
    }

    private static void walk(Object node,String order,List<Map<?,?>> matches,int depth,int[] visited) {
        if (depth>12 || ++visited[0]>10000) throw new IllegalArgumentException("PDD evidence nesting exceeded");
        if (node instanceof Map<?,?> map) {
            List<Object> identities=new ArrayList<>();
            for (String alias:List.of("orderSN","orderSn","order_sn")) if (map.get(alias)!=null) identities.add(map.get(alias));
            if (!identities.isEmpty() && identities.stream().allMatch(value->order.equals(String.valueOf(value)))) matches.add(map);
            for (Object value:map.values()) if (value instanceof Map<?,?> || value instanceof List<?>) walk(value,order,matches,depth+1,visited);
        } else if (node instanceof List<?> values) {
            for (Object value:values) walk(value,order,matches,depth+1,visited);
        }
    }
    private static int integer(Object value) {
        if (!(value instanceof Number) && !(value instanceof String)) return Integer.MIN_VALUE;
        try { return new BigDecimal(value.toString()).intValueExact(); }
        catch (RuntimeException invalid) { return Integer.MIN_VALUE; }
    }
    private static boolean numericEqual(Object left,Object right) {
        int a=integer(left), b=integer(right);
        return a!=Integer.MIN_VALUE && a==b;
    }
}
