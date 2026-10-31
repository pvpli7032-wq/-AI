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
                    case "14": return OrderStatus.RECEIVED;
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
                // -1未支付、1成团、3审核成功、4审核失败、8无佣金未确认可归一的状态。
                break;
            case JD:
                // 普通订单查询 validCode 的语义在仓库中未确认；一元购文档不能代替。
                break;
            default:
                break;
        }
        throw new IllegalArgumentException("未确认的联盟订单状态: " + platform + "/" + code);
    }
}
