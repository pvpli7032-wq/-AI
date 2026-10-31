package com.ruoyi.system.cps.order;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import com.ruoyi.union.adapter.DtkOrderEvidenceClient;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;
import com.ruoyi.union.adapter.UnionOrderLine;
import com.ruoyi.union.dtk.api.request.mastertool.DtkGetOrderDetailsRequest;
import com.ruoyi.union.dtk.api.response.mastertool.DtkGetOrderDetailsResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkGetOrderDetailsResultsItemsResponse;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;

/** Verified TBK order query only. Unverified APIs must fail durably, never invent status/paging rules. */
public final class DtkOrderCollectionFeed implements OrderCollectionFeed {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DtkOrderEvidenceClient client;
    private final OrderIngestionService ingestion;
    private final ZoneId zone;
    private final int queryType;

    public DtkOrderCollectionFeed(DtkOrderEvidenceClient client, OrderIngestionService ingestion, ZoneId zone, int queryType) {
        if (queryType < 1 || queryType > 3) throw new IllegalArgumentException("淘宝查询时间类型无效");
        this.client = java.util.Objects.requireNonNull(client);
        this.ingestion = java.util.Objects.requireNonNull(ingestion);
        this.zone = java.util.Objects.requireNonNull(zone); this.queryType = queryType;
    }
    @Override public boolean supports(Platform platform, OrderCollectionRetryQueue.TaskType type) {
        return platform == Platform.TBK && (type == OrderCollectionRetryQueue.TaskType.INCREMENTAL
            || type == OrderCollectionRetryQueue.TaskType.BACKFILL);
    }
    @Override public ResultPage fetch(Platform platform, OrderCollectionRetryQueue.TaskType type, TimeWindow window, String cursor) {
        if (!supports(platform, type) || window == null || window.from() == null || window.to() == null
            || !window.from().isBefore(window.to()) || window.from().getNano() != 0 || window.to().getNano() != 0
            || Duration.between(window.from(), window.to()).compareTo(Duration.ofHours(3)) > 0)
            throw new IllegalArgumentException("淘宝订单窗口无效");
        int pageNo = 1;
        String position = null;
        if (cursor != null) {
            int separator = cursor.indexOf(':');
            if (separator < 1) throw new IllegalArgumentException("淘宝游标无效");
            pageNo = Integer.parseInt(cursor.substring(0, separator));
            position = cursor.substring(separator + 1);
            if (pageNo < 2 || pageNo > 100 || position.isBlank()) throw new IllegalArgumentException("淘宝游标无效");
        }
        DtkGetOrderDetailsRequest request = new DtkGetOrderDetailsRequest();
        request.setStartTime(FORMAT.format(window.from().atZone(zone)));
        request.setEndTime(FORMAT.format(window.to().atZone(zone)));
        request.setQueryType(queryType); request.setPageSize(100); request.setPageNo(pageNo);
        if (cursor != null) { request.setPositionIndex(position); request.setJumpType(1); }
        var response = client.execute(request, (text, fetchedAt) -> ingestion.retainRaw(platform, text, fetchedAt));
        DtkApiResponse<DtkGetOrderDetailsResponse> envelope = response.data();
        if (envelope == null || !envelope.isSuccess() || envelope.getData() == null)
            throw new IllegalStateException("淘宝订单请求失败，原文已保留");
        var data = envelope.getData();
        if (data.getHasNext() == null || data.getResults() == null || data.getResults().getPublisherOrderDto() == null)
            throw new IllegalStateException("淘宝分页结果缺失，原文已保留");
        String next = null;
        if (data.getHasNext()) {
            if (data.getPositionIndex() == null || data.getPositionIndex().isBlank()
                || data.getPositionIndex().equals(position) || pageNo == 100)
                throw new IllegalStateException("淘宝游标缺失、重复或超过页码上限，须缩小窗口重采");
            next = (pageNo + 1) + ":" + data.getPositionIndex();
        }
        List<Entry> entries = new ArrayList<>();
        for (DtkGetOrderDetailsResultsItemsResponse row : data.getResults().getPublisherOrderDto()) {
            if (row == null) throw new IllegalStateException("淘宝订单行为空，原文已保留");
            String pid = row.getPubId() == null || row.getSiteId() == null || row.getAdzoneId() == null ? null
                : "mm_" + row.getPubId() + "_" + row.getSiteId() + "_" + row.getAdzoneId();
            UnionOrderLine line = new UnionOrderLine(platform, row.getTradeParentId(), row.getTradeId(), row.getItemId(),
                pid, null, money(row.getAlipayTotalPrice()), money(row.getPubSharePreFee()), money(row.getPubShareFee()),
                row.getTkStatus() == null ? null : row.getTkStatus().toString(), time(row.getTbPaidTime()), null,
                time(row.getTkEarningTime()), response.rawJson(), time(row.getModifiedTime()));
            entries.add(new Entry(IncomingOrder.from(line, response.fetchedAt()),
                row.getSiteId() == null ? null : row.getSiteId().toString(), false, null));
        }
        return new ResultPage(List.copyOf(entries), next);
    }
    private Instant time(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDateTime.parse(value, FORMAT).atZone(zone).toInstant();
    }
    private static BigDecimal money(String value) { return value == null || value.isBlank() ? null : new BigDecimal(value); }
}
