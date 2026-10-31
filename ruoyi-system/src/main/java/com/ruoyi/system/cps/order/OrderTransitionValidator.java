package com.ruoyi.system.cps.order;

import java.util.Collections;
import org.springframework.stereotype.Component;
import com.ruoyi.system.cps.contract.OrderStatus;
import com.ruoyi.system.cps.contract.StatusTransitions;

/** Validates without mutating state; callers persist any illegal event as an exception. */
@Component
public class OrderTransitionValidator
{
    public enum Outcome { INITIAL, APPLY, DUPLICATE, ILLEGAL }

    public record Result(Outcome outcome, String reason) {}

    /**
     * previousStatus is the recorded state when current is DISPUTED. The caller
     * must save current as previousStatus when entering DISPUTED.
     */
    public Result evaluate(OrderStatus current, OrderStatus target, OrderStatus previousStatus)
    {
        if (target == null)
        {
            return new Result(Outcome.ILLEGAL, "目标状态缺失");
        }
        if (current == null)
        {
            return target == OrderStatus.PAID
                ? new Result(Outcome.INITIAL, "")
                : new Result(Outcome.ILLEGAL, "首个状态不是已付款，需核对历史事件");
        }
        if (current == OrderStatus.DISPUTED
            && previousStatus != OrderStatus.RECEIVED && previousStatus != OrderStatus.SETTLED)
        {
            return new Result(Outcome.ILLEGAL, "维权前状态缺失或无效");
        }
        if (current == target)
        {
            return new Result(Outcome.DUPLICATE, "");
        }
        if (!StatusTransitions.ORDER.getOrDefault(current, Collections.emptySet()).contains(target))
        {
            return new Result(Outcome.ILLEGAL, "订单状态不允许从 " + current + " 变为 " + target);
        }
        if (current == OrderStatus.DISPUTED
            && (target == OrderStatus.RECEIVED || target == OrderStatus.SETTLED)
            && target != previousStatus)
        {
            return new Result(Outcome.ILLEGAL, "维权恢复状态与维权前状态不一致");
        }
        return new Result(Outcome.APPLY, "");
    }
}
