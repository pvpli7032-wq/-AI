package com.ruoyi.web.controller.miniapp;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.system.cps.mapper.OrderReadMapper;

/** My orders: user ID comes from the authenticated session and current attribution. */
@RestController
@RequestMapping("/miniapp/orders")
public class MiniappOrdersQueryController extends BaseController {
    private final OrderReadMapper orders;
    public MiniappOrdersQueryController(OrderReadMapper orders) { this.orders = orders; }
    @GetMapping
    public TableDataInfo list() {
        Long userId = getUserId();
        if (userId == null) throw new IllegalStateException("登录用户缺少身份");
        startPage();
        List<Map<String, Object>> rows = orders.listForUser(userId);
        TableDataInfo result = getDataTable(rows); // Capture PageHelper total before copying display rows.
        List<Map<String, Object>> display = new java.util.ArrayList<>(rows);
        // Unknown/legacy states are conservatively held, never displayed as credited or exposed as null.
        for (int i = 0; i < display.size(); i++) {
            Map<String, Object> row = display.get(i);
            if (!java.util.Set.of("待收货", "冻结中", "已结算", "已退款").contains(
                    java.util.Objects.toString(row.get("displayStatus"), ""))) {
                Map<String, Object> copy = new java.util.HashMap<>(row);
                copy.put("displayStatus", "冻结中");
                display.set(i, copy);
            }
        }
        result.setRows(display);
        return result;
    }
}
