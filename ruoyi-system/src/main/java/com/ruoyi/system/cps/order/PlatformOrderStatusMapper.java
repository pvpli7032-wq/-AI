package com.ruoyi.system.cps.order;

import org.springframework.stereotype.Component;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.union.adapter.Platform;

/**
 * Maps only statuses whose meaning is documented for the actual queried API.
 * Unknown or ambiguous codes must be retained as raw events and reviewed.
 */
@Component
public class PlatformOrderStatusMapper implements OrderStatusMapper
{
    public static final String TBK_RECEIVED_AS_SETTLED = "cps.order.tbk.received-as-settled";
    private final java.util.function.BooleanSupplier receivedAsSettled;
    public PlatformOrderStatusMapper() { this(() -> false); }
    public PlatformOrderStatusMapper(java.util.function.BooleanSupplier enabled) { this.receivedAsSettled = enabled; }
    @org.springframework.beans.factory.annotation.Autowired
    public PlatformOrderStatusMapper(com.ruoyi.system.service.ISysConfigService configs) {
        this(() -> "true".equalsIgnoreCase(configs.selectConfigByKey(TBK_RECEIVED_AS_SETTLED)));
    }
    @Override
    public OrderStatus map(Platform platform, String rawStatus)
    {
        if (platform == null || rawStatus == null || rawStatus.isBlank())
        {
            throw new IllegalArgumentException("联盟平台和原始订单状态不能为空");
        }
        String code = rawStatus.trim();
        switch (platform)
        {
            case TBK:
                // DtkGetOrderDetailsRequest: 12付款, 13关闭, 14确认收货, 3结算成功。
                switch (code)
                {
                    case "12": return OrderStatus.PAID;
                    case "13": return OrderStatus.INVALID;
                    case "14": return receivedAsSettled.getAsBoolean() ? OrderStatus.SETTLED : OrderStatus.RECEIVED;
                    case "3": return OrderStatus.SETTLED;
                    default: break;
                }
                break;
            case PDD:
                // DtkPddOrderIncrementSearchResponse: 0支付, 2收货, 5结算。
                switch (code)
                {
                    case "0": return OrderStatus.PAID;
                    case "2": return OrderStatus.RECEIVED;
                    case "5": return OrderStatus.SETTLED;
                    default: break;
                }
                // Additional codes require a verified, versioned query context: DtkOrderSemantics.pdd().
                break;
            case JD:
                // An unversioned raw code cannot establish payMonth/query provenance.
                // The concrete V2 collector uses DtkOrderSemantics.jd() and persists its interpretation for replay.
                break;
            default:
                break;
        }
        throw new IllegalArgumentException("未确认的联盟订单状态: " + platform + "/" + code);
    }
}
