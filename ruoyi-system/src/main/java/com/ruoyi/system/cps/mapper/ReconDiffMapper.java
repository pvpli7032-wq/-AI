package com.ruoyi.system.cps.mapper;

import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.system.cps.recon.ReconDiff;

public interface ReconDiffMapper {
    int insert(@Param("batchId") Long batchId, @Param("type") String type, @Param("refId") String refId,
        @Param("amount") BigDecimal amount, @Param("detail") String detail);
    List<ReconDiff> selectByBatch(@Param("batchId") Long batchId);
    ReconDiff lockById(@Param("id") Long id);
    int resolve(@Param("id") Long id, @Param("operatorId") Long operatorId, @Param("note") String note);
}
