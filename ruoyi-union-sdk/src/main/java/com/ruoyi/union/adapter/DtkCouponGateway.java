package com.ruoyi.union.adapter;

import java.util.Optional;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;

/**
 * Server-side credentials and promotion settings, supplied by the business layer.
 * No credential, PID or attribution identity is read from an HTTP request here.
 */
public interface DtkCouponGateway {
    <T> T execute(Platform platform, DtkApiRequest<T> request);
    String defaultPid(Platform platform);
    String jdUnionId();

    /** Optional checked quote from the secondary supplier; never used for conversion. */
    default Optional<CommissionQuote> quoteFallback(Platform platform, String itemId) {
        return Optional.empty();
    }
}
