package com.ruoyi.system.cps.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.cps.recon.*;

/** Line 12 owns reconciliation persistence. No account or ledger writes. */
public interface ReconBatchMapper {
    int lockImportGuard();
    ReconSource findSource(@Param("platform") String platform, @Param("sourceType") String sourceType,
        @Param("externalId") String externalId);
    ReconSource findCashSource(@Param("sourceType") String sourceType, @Param("externalId") String externalId);
    ReconSource findSourceById(@Param("id") Long id);
    int insertSource(@Param("request") ReconImportRequest request, @Param("operatorId") Long operatorId);
    List<ReconSource> selectSources(@Param("platform") String platform, @Param("from") LocalDate from,
        @Param("until") LocalDate until);
    ReconCoverage findCoverage(@Param("allocationNo") String allocationNo);
    int insertCoverage(@Param("request") ReconCoverageRequest request, @Param("operatorId") Long operatorId);
    java.math.BigDecimal coveredForReceipt(@Param("receiptId") Long receiptId);
    List<ExposurePosition> selectExposurePositions(@Param("platform") String platform);
    java.math.BigDecimal selectUnallocatedUserDebt();
    List<ReconOrder> selectOrders(@Param("platform") String platform, @Param("from") LocalDate from,
        @Param("until") LocalDate until);
    List<ReconPayout> selectPayoutPostings(@Param("from") LocalDate from, @Param("until") LocalDate until);
    List<ReconPayout> selectSuccessfulWithdrawals(@Param("from") LocalDate from, @Param("until") LocalDate until);
    int insertBatch(@Param("platform") String platform, @Param("period") String period,
        @Param("fingerprint") String fingerprint);
    ReconBatch lockBatch(@Param("fingerprint") String fingerprint);
    int completeBatch(@Param("id") Long id, @Param("diffCount") int diffCount);
    List<ReconBatch> selectBatches(@Param("platform") String platform, @Param("period") String period);
    List<String> selectConfigValues(@Param("key") String key);
    List<ExposureConfigValue> selectExposureConfigValues();
    int insertExposureConfig(@Param("key") String key, @Param("name") String name,
        @Param("value") String value, @Param("operatorId") long operatorId);
    int updateExposureConfig(@Param("key") String key, @Param("value") String value,
        @Param("operatorId") long operatorId);
    int appendExposurePolicyAudit(@Param("operatorId") long operatorId,
        @Param("beforeJson") String beforeJson, @Param("afterJson") String afterJson);
    ExposureSchedule lockExposureSchedule();
    int completeExposureCapture(@Param("capturedAt") java.time.LocalDateTime capturedAt);
    int appendExposure(@Param("platform") String platform, @Param("amount") java.math.BigDecimal amount);
    java.math.BigDecimal peakExposure(@Param("platform") String platform);
}
