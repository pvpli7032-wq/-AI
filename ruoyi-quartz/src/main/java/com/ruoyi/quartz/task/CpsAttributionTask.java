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
    private final IntUnaryOperator cleanupTokens;
    private final IntUnaryOperator cleanupReservations;

    public CpsAttributionTask(
            @Qualifier("cpsExpireUnclaimedOperation") IntUnaryOperator expireUnclaimed,
            @Qualifier("cpsRetryAttributionSettlementOperation") IntUnaryOperator retrySettlement,
            @Qualifier("cpsCleanupAttributionTokensOperation") IntUnaryOperator cleanupTokens,
            @Qualifier("cpsCleanupPidReservationsOperation") IntUnaryOperator cleanupReservations) {
        this.expireUnclaimed = expireUnclaimed;
        this.retrySettlement = retrySettlement;
        this.cleanupTokens = cleanupTokens;
        this.cleanupReservations = cleanupReservations;
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

    public void cleanupTokens(Integer batchSize) {
        int removed = cleanupTokens.applyAsInt(validateBatchSize(batchSize));
        LOG.info("Attribution token cleanup completed: removed={}", removed);
    }

    public void cleanupReservations(Integer batchSize) {
        int removed = cleanupReservations.applyAsInt(validateBatchSize(batchSize));
        LOG.info("Expired PID reservation cleanup completed: removed={}", removed);
    }

    private int validateBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("Attribution task batchSize must be between 1 and 500");
        }
        return batchSize;
    }
}
