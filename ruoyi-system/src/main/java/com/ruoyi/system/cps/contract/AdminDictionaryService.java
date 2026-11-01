package com.ruoyi.system.cps.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Shared, read-only options for the administrative UI. API values are never presentation labels. */
@Service
public class AdminDictionaryService {
    public Map<String, Object> dictionary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("platforms", options(new String[][] {
            {"tbk", "淘宝"}, {"jd", "京东"}, {"pdd", "拼多多"}
        }));
        result.put("orderStatuses", options(new String[][] {
            {"PAID", "待收货"}, {"RECEIVED", "冻结中"}, {"SETTLED", "已结算"},
            {"INVALID", "已退款"}, {"DISPUTED", "冻结中"}, {"PARTIAL_REFUND", "已退款"},
            {"FULL_REFUND", "已退款"}, {"PENALIZED", "冻结中"}
        }));
        result.put("withdrawalStatuses", options(new String[][] {
            {"MANUAL_REVIEW", "审核中"}, {"APPROVED", "打款中"}, {"SUBMITTING", "打款中"},
            {"PROCESSING", "打款中"}, {"UNKNOWN", "打款中"}, {"AWAITING_CONFIRMATION", "打款中"},
            {"TIMED_OUT", "打款中"}, {"SUCCEEDED", "已打款"}, {"FAILED", "打款失败"},
            {"REJECTED", "审核驳回"}
        }));
        result.put("attributionMethods", options(new String[][] {
            {"ATTRIBUTION_TOKEN", "平台确定标识"}, {"TRACKING_PID", "追踪 PID"},
            {"CLAIM", "手动提交"}, {"MANUAL", "人工处理"}, {"UNATTRIBUTED", "无主"}
        }));
        result.put("flowTypes", flowTypes());
        return result;
    }

    private static List<Map<String, String>> options(String[][] entries) {
        return java.util.Arrays.stream(entries).map(entry -> option(entry[0], entry[1])).toList();
    }

    private static Map<String, String> option(String value, String label) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("value", value);
        item.put("label", label);
        return item;
    }

    private static List<Map<String, String>> flowTypes() {
        // These are persisted ledger flow_type values emitted by current ledger/settlement/withdrawal code.
        return options(new String[][] {
            {"settle_freeze", "订单冻结"}, {"settle_release", "订单结算"},
            {"income_self", "自购佣金入账"}, {"income_promotion", "推广佣金入账"},
            {"refund_self", "自购佣金追回"}, {"refund_promotion", "推广佣金追回"},
            {"reward_freeze", "奖励冻结"}, {"reward_credit", "奖励入账"},
            {"reward_recovery", "奖励追回"}, {"withdraw", "提现"}, {"reversal", "冲正"}
        });
    }
}
