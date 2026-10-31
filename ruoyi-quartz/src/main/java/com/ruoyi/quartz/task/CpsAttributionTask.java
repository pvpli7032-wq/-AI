package com.ruoyi.quartz.task;

import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Independent Quartz targets; transactional locks and retries stay in the business service. */
@Component("cpsAttributionTask")
public class CpsAttributionTask {
    private static final Logger LOG = LoggerFactory.getLogger(CpsAttributionTask.class);
    private final IntUnaryOperator expireUnclaimed;
    private final IntUnaryOperator retrySettlement;

    public CpsAttributionTask(
            @Qualifier("cpsExpireUnclaimedOperation") IntUnaryOperator expireUnclaimed,
            @Qualifier("cpsRetryAttributionSettlementOperation") IntUnaryOperator retrySettlement) {
        this.expireUnclaimed = expireUnclaimed;
        this.retrySettlement = retrySettlement;
    }

    // JobInvokeUtil resolves numeric arguments as Integer.class, not int.class.
    public void expireUnclaimed(Integer batchSize) {
        int expired = expireUnclaimed.applyAsInt(validateBatchSize(batchSize));
        LOG.info("Attribution expiry completed: expired={}", expired);
    }

    public void retrySettlement(Integer batchSize) {
        int delivered = retrySettlement.applyAsInt(validateBatchSize(batchSize));
        LOG.info("Attribution settlement retry completed: delivered={}", delivered);
    }

    private int validateBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Attribution task batchSize must be between 1 and 500");
        }
        return batchSize;
    }
}
