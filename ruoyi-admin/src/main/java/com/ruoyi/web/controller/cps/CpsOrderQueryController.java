package com.ruoyi.web.controller.cps;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.system.cps.mapper.OrderReadMapper;

/** Administrative order list and full attribution/status history. */
@RestController
@RequestMapping("/cps/orders")
public class CpsOrderQueryController extends BaseController {
    private final OrderReadMapper orders;
    public CpsOrderQueryController(OrderReadMapper orders) { this.orders = orders; }

    @PreAuthorize("@ss.hasPermi('cps:order:list')")
    @GetMapping
    public TableDataInfo list(@RequestParam(required = false) String platform,
            @RequestParam(required = false) String status, @RequestParam(required = false) String method) {
        startPage();
        return getDataTable(orders.listAdmin(platform, status, method));
    }

    @PreAuthorize("@ss.hasPermi('cps:order:query')")
    @GetMapping("/{id}")
    public AjaxResult detail(@PathVariable Long id) {
        Map<String, Object> order = orders.findAdmin(id);
        if (order == null) return error("订单不存在");
        return success(Map.of("order", order, "attributionHistory", orders.attributionHistory(id),
            "statusHistory", orders.statusHistory(id)));
    }
}
