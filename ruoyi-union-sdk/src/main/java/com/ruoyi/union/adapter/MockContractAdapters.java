package com.ruoyi.union.adapter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Test/dev startup placeholders only; no real alliance call or fabricated result. */
@Configuration
@Profile({"test", "dev"})
public class MockContractAdapters {
    @Bean UnionAdapter mockTbkAdapter() { return placeholder(Platform.TBK); }
    @Bean UnionAdapter mockJdAdapter() { return placeholder(Platform.JD); }
    @Bean UnionAdapter mockPddAdapter() { return placeholder(Platform.PDD); }

    private UnionAdapter placeholder(Platform platform) {
        return new UnionAdapter() {
            public Platform platform() { return platform; }
            public AdapterKind kind() { return AdapterKind.MOCK; }
            public ParsedItem parse(String input) { throw unavailable(); }
            public CommissionQuote quote(String itemId) { throw unavailable(); }
            public ConvertedLink convert(String itemId, String token) { throw unavailable(); }
            public Page<UnionOrderLine> fetchOrders(TimeWindow window, String cursor) { throw unavailable(); }
            public Page<UnionCase> fetchRefunds(TimeWindow window, String cursor) { throw unavailable(); }
            public Page<UnionCase> fetchPenalties(TimeWindow window, String cursor) { throw unavailable(); }
            private UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException("Mock contract placeholder cannot call alliance APIs");
            }
        };
    }
}
