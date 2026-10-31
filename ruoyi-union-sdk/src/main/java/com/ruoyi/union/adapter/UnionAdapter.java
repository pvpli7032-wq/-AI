package com.ruoyi.union.adapter;

import java.util.List;

/** Alliance boundary. Implementations and platform status mappings belong to later lines. */
public interface UnionAdapter {
    Platform platform();
    AdapterKind kind();
    ParsedItem parse(String input);
    CommissionQuote quote(String itemId);
    ConvertedLink convert(String itemId, String attributionToken);
    Page<UnionOrderLine> fetchOrders(TimeWindow window, String cursor);
    Page<UnionCase> fetchRefunds(TimeWindow window, String cursor);
    Page<UnionCase> fetchPenalties(TimeWindow window, String cursor);
}
