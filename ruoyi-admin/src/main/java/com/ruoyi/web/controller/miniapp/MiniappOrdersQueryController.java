package com.ruoyi.web.controller.miniapp;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.exception.ServiceException;
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
        if (rows.stream().anyMatch(row -> row.get("displayStatus") == null))
            throw new ServiceException("订单状态待确认，请稍后重试");
        return getDataTable(rows);
    }
}
