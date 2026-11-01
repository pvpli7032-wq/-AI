package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.mapper.OrderAuxiliaryEvidenceMapper;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionOrderLine;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkJdOrderQueryV2Request;
import com.ruoyi.union.dtk.api.request.search.DtkPddOrderIncrementSearchRequest;
import com.ruoyi.union.dtk.api.request.search.DtkTbkScRelationRefundRequest;
import com.ruoyi.union.dtk.api.request.search.DtkTbkScPunishOrderGetRequest;

/** Versioned DTK sources. All pages/errors are archived before parsing. No official SDK or agent dependency. */
public final class DtkAdditionalOrderCollectionFeed implements OrderCollectionFeed {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DtkOrderEvidenceClient client;
    private final OrderIngestionService ingestion;
    private final OrderCollectionSettings settings;
    private final OrderAuxiliaryEvidenceMapper evidence;
    private final ZoneId zone;
    private final Clock clock;
    private final String jdKey;
    private final int refundBizType;
    public DtkAdditionalOrderCollectionFeed(DtkOrderEvidenceClient client, OrderIngestionService ingestion,
            OrderCollectionSettings settings, OrderAuxiliaryEvidenceMapper evidence, ZoneId zone, Clock clock,
            String jdKey, int refundBizType) {
        this.client = java.util.Objects.requireNonNull(client); this.ingestion = java.util.Objects.requireNonNull(ingestion);
        this.settings = java.util.Objects.requireNonNull(settings); this.evidence = java.util.Objects.requireNonNull(evidence);
        this.zone = java.util.Objects.requireNonNull(zone); this.clock = java.util.Objects.requireNonNull(clock);
        this.jdKey = jdKey; this.refundBizType = refundBizType;
    }
    @Override public boolean supports(Platform platform, OrderCollectionRetryQueue.TaskType type) {
        return ((platform == Platform.JD || platform == Platform.PDD)
                && (type == OrderCollectionRetryQueue.TaskType.INCREMENTAL || type == OrderCollectionRetryQueue.TaskType.BACKFILL))
            || (platform == Platform.TBK && (type == OrderCollectionRetryQueue.TaskType.REFUND || type == OrderCollectionRetryQueue.TaskType.PENALTY));
    }
    @Override public ResultPage fetch(Platform platform, OrderCollectionRetryQueue.TaskType type, TimeWindow window, String cursor) {
        if (!supports(platform, type) || cursor != null) throw new IllegalArgumentException("数据源或游标无效");
        long maxSeconds = platform == Platform.JD ? 3600 : type == OrderCollectionRetryQueue.TaskType.PENALTY ? 2592000 : 86400;
        validateWindow(window, maxSeconds);
        if (platform == Platform.JD) {
            List<Entry> rows = new ArrayList<>();
            boolean complete = jd(window, rows);
            return new ResultPage(List.copyOf(rows), null, complete);
        }
        if (platform == Platform.PDD) return pdd(window);
        return tbkCases(type, window);
    }
    private record Raw(String text, Instant fetchedAt, long id, JSONObject envelope) {}
    private Raw request(Platform platform, DtkApiRequest<?> request) {
        long[] id = {0};
        var response = client.executeRaw(request, (text, fetched) -> id[0] = ingestion.retainRaw(platform, text, fetched));
        try {
            JSONObject root = JSON.parseObject(response.rawJson());
            if (root == null || !root.containsKey("code")) throw new IllegalArgumentException();
            return new Raw(response.rawJson(), response.fetchedAt(), id[0], root);
        } catch (RuntimeException bad) {
            ingestion.recordSourceException(id[0], "联盟响应JSON或错误码缺失");
            throw new IllegalStateException("联盟响应无效，已保留原文");
        }
    }
    private JSONObject data(Raw raw) {
        if (!"0".equals(raw.envelope().getString("code")) || raw.envelope().getJSONObject("data") == null) {
            ingestion.recordSourceException(raw.id(), "联盟请求失败，窗口待补偿");
            throw new IllegalStateException("联盟请求失败，已保留原文");
        }
        return raw.envelope().getJSONObject("data");
    }
    private boolean jd(TimeWindow window, List<Entry> output) {
        if (jdKey == null || jdKey.isBlank()) throw new IllegalStateException("京东订单授权key尚未配置");
        boolean complete = true;
        for (int page = 1; page <= 10000; page++) {
            DtkJdOrderQueryV2Request query = new DtkJdOrderQueryV2Request();
            query.setKey(jdKey); query.setType("3"); query.setPageNo(page); query.setPageSize(100);
            query.setStartTime(format(window.from())); query.setEndTime(format(window.to()));
            Raw raw = request(Platform.JD, query);
            if ("2002452".equals(raw.envelope().getString("code"))) {
                ingestion.recordSourceException(raw.id(), "京东2002452订单超量，缩小窗口重查");
                long seconds = Duration.between(window.from(), window.to()).getSeconds();
                if (seconds <= 1) throw new IllegalStateException("京东一秒窗口仍超量，需要人工补偿");
                Instant middle = window.from().plusSeconds(seconds / 2);
                boolean first = jd(new TimeWindow(window.from(), middle), output);
                boolean second = jd(new TimeWindow(middle, window.to()), output);
                return complete && first && second;
            }
            JSONObject payload = data(raw);
            boolean more;
            try { more = DtkOrderSemantics.hasMore(value(payload, "hasMore", "has_more")); }
            catch (RuntimeException bad) {
                ingestion.recordSourceException(raw.id(), "京东hasMore缺失或无法解析"); throw bad;
            }
            JSONArray list = payload.getJSONArray("list");
            if (list == null || (more && list.isEmpty())) throw new IllegalStateException("京东分页列表缺失或空页仍有后续");
            for (int i = 0; i < list.size(); i++) {
                JSONObject row = list.getJSONObject(i);
                String code = text(row, "validCode");
                OrderStatus target = DtkOrderSemantics.jd(code, text(row, "payMonth"), clock.instant().atZone(zone).toLocalDate());
                if (target == null) {
                    ingestion.recordSourceException(raw.id(), "京东未付款或未确认validCode=" + safeCode(code));
                    complete = false; continue;
                }
                BigDecimal returned = number(row, "skuReturnNum"), frozen = number(row, "skuFrozenNum");
                if (target != OrderStatus.INVALID && positive(returned)) {
                    BigDecimal quantity = number(row, "skuNum");
                    if (quantity == null || quantity.signum() <= 0 || returned.compareTo(quantity) > 0)
                        throw new IllegalStateException("京东退货数量无效");
                    target = returned.compareTo(quantity) == 0 ? OrderStatus.FULL_REFUND : OrderStatus.PARTIAL_REFUND;
                } else if (target != OrderStatus.INVALID && positive(frozen)) target = OrderStatus.DISPUTED;
                BigDecimal price = number(row, "price"), quantity = number(row, "skuNum");
                UnionOrderLine line = new UnionOrderLine(Platform.JD, text(row, "orderId"), text(row, "id"), text(row, "skuId"),
                    text(row, "pid") == null ? text(row, "positionId") : text(row, "pid"), text(row, "subUnionId"),
                    price == null || quantity == null ? null : price.multiply(quantity), number(row, "estimateFee"),
                    number(row, "actualFee"), code, time(text(row, "orderTime")), time(text(row, "finishTime")),
                    null, raw.text(), time(text(row, "modifyTime")));
                // Settled lines are intentionally not skipped: updates may carry returns up to 90 days later.
                output.add(new Entry(IncomingOrder.from(line, raw.fetchedAt()).resolved(target, false, "JD_V2"),
                    text(row, "siteId"), false, null));
            }
            if (!more) return complete;
        }
        throw new IllegalStateException("京东页数达到安全上限，窗口不能标记完成");
    }
    private ResultPage pdd(TimeWindow window) {
        DtkPddOrderIncrementSearchRequest query = new DtkPddOrderIncrementSearchRequest();
        query.setStartUpdateTime(Long.toString(window.from().getEpochSecond()));
        query.setEndUpdateTime(Long.toString(window.to().getEpochSecond())); query.setPageSize(100);
        // Do not interpret the inherited pageId semantics as documentation for this specific endpoint.
        Raw raw = request(Platform.PDD, query);
        JSONObject payload = data(raw);
        JSONArray list = payload.getJSONArray("list");
        if (list == null) throw new IllegalStateException("拼多多订单列表缺失");
        Long total = payload.getLong("totalNum");
        boolean complete = total != null && total >= 0 && total == list.size();
        HashSet<String> ids = new HashSet<>();
        List<Entry> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JSONObject row = list.getJSONObject(i);
            String orderNo = text(row, "orderSN");
            if (orderNo == null || !ids.add(orderNo)) complete = false;
            String code = text(row, "orderStatus");
            OrderStatus target = DtkOrderSemantics.pdd(code);
            if (target == null) {
                ingestion.recordSourceException(raw.id(), "拼多多未付款或未知orderStatus=" + safeCode(code));
                complete = false; continue;
            }
            UnionOrderLine line = new UnionOrderLine(Platform.PDD, orderNo, "", text(row, "goodsId"), text(row, "pid"),
                text(row, "customParameters"), cents(number(row, "orderAmount")), cents(number(row, "promotionAmount")),
                null, code, epoch(text(row, "orderPayTime")), epoch(text(row, "orderReceiveTime")),
                epoch(text(row, "orderSettleTime")), raw.text(), epoch(text(row, "orderModifyAt")));
            // PDD does not expose siteId. Ownership is PID-only with an explicit empty site whitelist token.
            rows.add(new Entry(IncomingOrder.from(line, raw.fetchedAt()).resolved(target, false, "PDD_INCREMENT"), "", false, null));
        }
        if (!complete) ingestion.recordSourceException(raw.id(), "拼多多分页语义未确认或累计条数不等于totalNum，窗口待补偿");
        return new ResultPage(List.copyOf(rows), null, complete);
    }
    private ResultPage tbkCases(OrderCollectionRetryQueue.TaskType type, TimeWindow window) {
        boolean penalty = type == OrderCollectionRetryQueue.TaskType.PENALTY;
        if (!penalty && refundBizType != 1 && refundBizType != 2) throw new IllegalStateException("淘宝维权bizType尚未配置");
        long seen = 0;
        Long expectedTotal = null;
        boolean complete = true;
        List<Entry> output = new ArrayList<>();
        HashSet<String> pages = new HashSet<>();
        for (int page = 1; page <= 100; page++) {
            DtkApiRequest<?> query;
            if (penalty) {
                DtkTbkScPunishOrderGetRequest q = new DtkTbkScPunishOrderGetRequest();
                q.setStartTime(format(window.from()));
                q.setSpan((int)((Duration.between(window.from(), window.to()).getSeconds() + 86399) / 86400));
                q.setPageNo(page); q.setPageSize(100); query = q;
            } else {
                DtkTbkScRelationRefundRequest q = new DtkTbkScRelationRefundRequest();
                q.setSearchType(5); q.setRefundType(0); q.setBizType(refundBizType); q.setStartTime(format(window.from()));
                q.setPageNo(page); q.setPageSize(100); query = q;
            }
            Raw raw = request(Platform.TBK, query);
            JSONObject payload = data(raw);
            Long total = longValue(payload, "totalCount", "total_count");
            JSONArray list = payload.getJSONArray("results");
            if (total == null || total < 0 || list == null || (expectedTotal != null && !total.equals(expectedTotal)))
                throw new IllegalStateException("淘宝维权处罚总数缺失或翻页期间变化，窗口待补偿");
            expectedTotal = total;
            if (!list.isEmpty() && !pages.add(list.toJSONString())) throw new IllegalStateException("淘宝维权处罚分页重复");
            seen += list.size();
            if (seen > total || (seen < total && list.isEmpty())) throw new IllegalStateException("淘宝维权处罚分页数量不一致");
            for (int i = 0; i < list.size(); i++) {
                JSONObject row = list.getJSONObject(i);
                if (penalty) complete &= penalty(row, raw, type, window);
                else {
                    Entry entry = refund(row, raw, window);
                    if (entry == null) complete = false;
                    else output.add(entry);
                }
            }
            if (seen == total) return new ResultPage(List.copyOf(output), null, complete);
        }
        throw new IllegalStateException("淘宝维权处罚超过100页，窗口不能标记完成");
    }
    private boolean penalty(JSONObject row, Raw raw, OrderCollectionRetryQueue.TaskType type, TimeWindow window) {
        String site = text(row, "tkSiteId", "tk_site_id"), adzone = text(row, "tkAdzoneId", "tk_adzone_id");
        String pub = text(row, "tkPubId", "tk_pub_id");
        String pid = pub == null || site == null || adzone == null ? null : "mm_" + pub + "_" + site + "_" + adzone;
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, text(row, "tbTradeId", "tb_trade_id"), "", null, pid,
            null, null, null, null, null, null, null, null, raw.text(), null);
        var work = new OrderCollectionSettings.Work(UUID.randomUUID(), Platform.TBK, type, window);
        Entry entry = new Entry(IncomingOrder.from(line, raw.fetchedAt()), site, false, null);
        if (!settings.belongsToSystem(work, entry)) { settings.auditRejected(work, entry); return true; }
        String status = text(row, "punishStatus", "punish_status"), violation = text(row, "violationType", "violation_type");
        if (!("0".equals(status) || "1".equals(status)) || violation == null || violation.isBlank() || violation.length() > 255) {
            ingestion.recordSourceException(raw.id(), "淘宝处罚状态或类型无效"); return false;
        }
        Map<String,Object> values = new HashMap<>();
        String canonical = JSON.toJSONString(new java.util.TreeMap<>(row));
        values.put("key", hash(canonical)); values.put("rawId", raw.id()); values.put("payload", row.toJSONString());
        values.put("status", status); values.put("violation", violation); values.put("adzone", adzone); values.put("site", site);
        evidence.appendAutomaticPenalty(values); // 0 means duplicate. Never calls order ingestion/settlement.
        return true;
    }
    private Entry refund(JSONObject row, Raw raw, TimeWindow window) {
        String order = text(row, "tbTradeParentId", "tb_trade_parent_id"), sub = text(row, "tbTradeId", "tb_trade_id");
        if (order == null || sub == null) {
            ingestion.recordSourceException(raw.id(), "淘宝维权订单标识缺失"); return null;
        }
        Map<String,Object> stored = evidence.findTbk(Map.of("orderNo", order, "subOrderNo", sub));
        if (stored == null) {
            ingestion.recordSourceException(raw.id(), "淘宝维权找不到原订单，不能猜测推广位或补造已付款状态"); return null;
        }
        String pid = string(stored.get("pid")), pub = text(row, "tkPubId", "tk_pub_id");
        String[] parts = pid == null ? new String[0] : pid.split("_", -1);
        if (parts.length != 4 || !"mm".equals(parts[0]) || pub == null || !pub.equals(parts[1])) {
            ingestion.recordSourceException(raw.id(), "淘宝维权推广者与原订单PID无法核验"); return null;
        }
        String code = text(row, "refundStatus", "refund_status");
        // DTK id=87: 14/15 deduct from the SELLER, not the affiliate. Keep evidence and
        // compensation pending; neither an order event nor any rebate recovery is authorized.
        if ("14".equals(code) || "15".equals(code)) {
            ingestion.recordSourceException(raw.id(), "淘宝维权refund_status=" + code
                + ("14".equals(code) ? "：卖家侧补扣等待" : "：卖家侧补扣成功")
                + "，不是淘客佣金追回；仅留证不动返利，窗口待补偿");
            return null;
        }
        OrderStatus target = DtkOrderSemantics.tbkRefund(code, number(row, "refundFee", "refund_fee"),
            number(row, "tbTradeFinishPrice", "tb_trade_finish_price"));
        if (target == null && !"3".equals(code)) {
            ingestion.recordSourceException(raw.id(), "淘宝维权未确认refund_status=" + safeCode(code) + "，不动返利"); return null;
        }
        Instant modified = time(text(row, "modifiedTime", "modified_time"));
        UnionOrderLine line = new UnionOrderLine(Platform.TBK, order, sub, string(stored.get("item_id")), pid,
            string(stored.get("attribution_token")), decimal(stored.get("order_amount")), decimal(stored.get("estimated_commission")),
            decimal(stored.get("actual_commission")), code, storedTime(stored, "paid_time", "cps_paid"),
            storedTime(stored, "received_time", null), storedTime(stored, "settle_time", "cps_settled"), raw.text(), modified);
        // This API has no endTime: retain/process every returned modification; later overlap is deduplicated.
        return new Entry(IncomingOrder.from(line, raw.fetchedAt()).resolved(target, "3".equals(code), "TBK_REFUND"), parts[2], false, null);
    }
    private String format(Instant value) { return FORMAT.format(value.atZone(zone)); }
    private Instant time(String value) {
        if (value == null || value.isBlank() || "0".equals(value)) return null;
        if (value.matches("\\d{10}")) return Instant.ofEpochSecond(Long.parseLong(value));
        if (value.matches("\\d{13}")) return Instant.ofEpochMilli(Long.parseLong(value));
        return LocalDateTime.parse(value.trim().replace('/', '-').replaceAll(" +", " "), FORMAT).atZone(zone).toInstant();
    }
    private static Instant epoch(String value) { return value == null || value.isBlank() || "0".equals(value) ? null : Instant.ofEpochSecond(Long.parseLong(value)); }
    private static Object value(JSONObject row, String... keys) { for (String key : keys) if (row.containsKey(key)) return row.get(key); return null; }
    private static String text(JSONObject row, String... keys) { return string(value(row, keys)); }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static BigDecimal number(JSONObject row, String... keys) { return decimal(value(row, keys)); }
    private static BigDecimal decimal(Object value) { return value == null || value.toString().isBlank() ? null : new BigDecimal(value.toString()); }
    private static Long longValue(JSONObject row, String... keys) { Object n = value(row, keys); return n == null ? null : new BigDecimal(n.toString()).longValueExact(); }
    private static BigDecimal cents(BigDecimal n) { return n == null ? null : n.movePointLeft(2); }
    private static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
    private static String safeCode(String value) { return value != null && value.matches("-?[0-9]{1,6}") ? value : "UNKNOWN"; }
    private static Instant storedTime(Map<String,Object> row, String field, String prefix) {
        if (prefix != null && row.get(prefix + "_second") instanceof Number s && row.get(prefix + "_nano") instanceof Number n)
            return Instant.ofEpochSecond(s.longValue(), n.longValue());
        Object value = row.get(field);
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof LocalDateTime local) return local.toInstant(java.time.ZoneOffset.UTC);
        return null;
    }
    private static String hash(String text) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void validateWindow(TimeWindow window, long max) {
        if (window == null || window.from() == null || window.to() == null || !window.from().isBefore(window.to())
            || window.from().getNano() != 0 || window.to().getNano() != 0 || Duration.between(window.from(), window.to()).getSeconds() > max)
            throw new IllegalArgumentException("联盟查询窗口无效或超过接口上限");
    }
}
