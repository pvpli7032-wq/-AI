package com.ruoyi.union.adapter;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import com.ruoyi.union.dtk.api.client.DtkClient;
import com.ruoyi.union.dtk.api.client.AbstractDtkApiClient;
import com.ruoyi.union.dtk.api.request.mastertool.DtkGetOrderDetailsRequest;
import com.ruoyi.union.dtk.api.request.mastertool.DtkJdOrderQueryV2Request;
import com.ruoyi.union.dtk.api.response.base.DtkApiResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkGetOrderDetailsResponse;
import com.ruoyi.union.dtk.api.response.mastertool.DtkJdOrderQueryV2Response;

/** Low-level, mockable DTK requests. Returned provider DTOs have not been normalized. */
public final class DtkOrderPageClient {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DtkClient client;
    private final ZoneId providerZone;

    public DtkOrderPageClient(DtkClient client, ZoneId providerZone) {
        this.client = Objects.requireNonNull(client);
        this.providerZone = Objects.requireNonNull(providerZone);
    }

    public DtkGetOrderDetailsResponse fetchTbk(TimeWindow window, int queryType, int pageNo, String positionIndex) {
        validate(window, Duration.ofHours(3));
        if (queryType < 1 || queryType > 3) throw new IllegalArgumentException("Invalid TBK query type");
        if (pageNo < 1 || pageNo > 100 || (pageNo > 1 && (positionIndex == null || positionIndex.isBlank()))) {
            throw new IllegalArgumentException("TBK continuation requires a page number and provider positionIndex");
        }
        DtkGetOrderDetailsRequest request = new DtkGetOrderDetailsRequest();
        request.setStartTime(FORMAT.format(window.from().atZone(providerZone)));
        request.setEndTime(FORMAT.format(window.to().atZone(providerZone)));
        request.setPageSize(100);
        request.setPageNo(pageNo);
        request.setQueryType(queryType);
        if (pageNo > 1) {
            request.setPositionIndex(positionIndex);
            request.setJumpType(1);
        }
        DtkApiResponse<DtkGetOrderDetailsResponse> response = client.execute(request);
        DtkGetOrderDetailsResponse data = requireData(response);
        if (data.getHasNext() == null) {
            throw new IllegalStateException("TBK response lacks a hasNext marker");
        }
        if (Boolean.TRUE.equals(data.getHasNext()) && (data.getPositionIndex() == null || data.getPositionIndex().isBlank())) {
            throw new IllegalStateException("TBK returned hasNext without positionIndex");
        }
        return data;
    }

    public DtkJdOrderQueryV2Response fetchJd(TimeWindow window, int pageNo, String authorizationKey) {
        validate(window, Duration.ofHours(1));
        if (pageNo < 1) throw new IllegalArgumentException("JD page number must be positive");
        if (authorizationKey == null || authorizationKey.isBlank()) {
            throw new IllegalArgumentException("JD authorization key is required");
        }
        if (client instanceof AbstractDtkApiClient) {
            throw new IllegalStateException("JD order requests require a client that does not log authorization keys; existing DTK client needs the line 14 logging fix");
        }
        DtkJdOrderQueryV2Request request = new DtkJdOrderQueryV2Request();
        request.setKey(authorizationKey);
        request.setStartTime(FORMAT.format(window.from().atZone(providerZone)));
        request.setEndTime(FORMAT.format(window.to().atZone(providerZone)));
        request.setPageNo(pageNo);
        request.setType("3");
        DtkJdOrderQueryV2Response data = requireData(client.execute(request));
        if (data.getHasMore() == null || (data.getHasMore() != 0 && data.getHasMore() != 1)) {
            throw new IllegalStateException("JD response lacks a valid hasMore marker");
        }
        return data;
    }

    private static <T> T requireData(DtkApiResponse<T> response) {
        if (response == null || !response.isSuccess() || response.getData() == null) {
            throw new IllegalStateException("DTK order request failed or returned no data");
        }
        return response.getData();
    }

    private static void validate(TimeWindow window, Duration limit) {
        if (window == null || window.from() == null || window.to() == null || !window.from().isBefore(window.to())
            || window.from().getNano() != 0 || window.to().getNano() != 0
            || Duration.between(window.from(), window.to()).compareTo(limit) > 0) {
            throw new IllegalArgumentException("Invalid DTK order time window");
        }
    }
}
