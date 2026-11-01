package com.ruoyi.web.controller.cps;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.system.cps.order.OrderIngestionService;

/** Replays only persisted input; arbitrary caller-supplied money, status and timestamps are not accepted. */
@RestController
@RequestMapping("/cps/orders/raw")
public class CpsOrderReplayController extends BaseController {
    private final OrderIngestionService ingestion;
    public CpsOrderReplayController(OrderIngestionService ingestion) { this.ingestion = ingestion; }
    @PreAuthorize("@ss.hasPermi('cps:order:replay')")
    @Log(title = "订单原始数据重放", businessType = BusinessType.UPDATE)
    @PostMapping("/{rawId}/replay")
    public AjaxResult replay(@PathVariable long rawId) { return success(ingestion.replay(rawId)); }
}
