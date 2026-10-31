package com.ruoyi.system.cps.withdraw;

import static org.junit.Assert.*;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.Test;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.ruoyi.common.exception.ServiceException;

/** Synthetic caps only; these tests exercise the opt-in and persisted-snapshot contract. */
public class AutomaticWithdrawalPolicyTest {
    private JSONObject policy() {
        return JSON.parseObject("""
            {"enabled":true,"channel":"mock","businessTimezone":"UTC",
             "maximum":"0","dailyCount":0,"recipientDailyCount":0,"monthlyAmount":"0","monthDays":"0",
             "integerOnly":false,"requireReceivedOrder":false,"realNameSingle":"0","realNameMonthly":"0",
             "reviewAmount":"0","reviewFirstCount":0,"channelSingle":"0","channelDaily":"0",
             "channelUserDaily":"0","groups":{}}
            """);
    }

    private WithdrawalPolicy parse(JSONObject json) {
        return WithdrawalPolicy.parse(JSON.toJSONString(json, JSONWriter.Feature.WriteNulls), "0.01");
    }

    @Test
    public void oldConfigurationAndOldSnapshotsDefaultToAutomaticPaymentDisabled() {
        WithdrawalPolicy parsed = parse(policy());
        assertFalse(parsed.autoPayEnabled());
        assertEquals(new BigDecimal("0.00"), parsed.autoPayBelowAmount());
        assertFalse(parsed.permitsAutomaticPayment(new BigDecimal("0.01")));
        assertEquals("AUTO_PAY_DISABLED", parsed.automaticReviewReason(new BigDecimal("0.01")));
        JSONObject legacySnapshot = policy();
        legacySnapshot.put("minimumAtApplication", "0.01");
        assertFalse(WithdrawalPolicy.fromSnapshot(legacySnapshot.toJSONString()).autoPayEnabled());
        assertEquals(Boolean.FALSE, JSON.parseObject(parsed.snapshot()).getBoolean("autoPayEnabled"));
    }

    @Test
    public void automaticPaymentRequiresBothSwitchAndPositiveCapAndIsStrictlyBelowTheCap() {
        JSONObject config = policy();
        config.put("autoPayEnabled", true);
        config.put("autoPayBelowAmount", "10.00");
        WithdrawalPolicy enabled = parse(config);
        assertTrue(enabled.permitsAutomaticPayment(new BigDecimal("9.99")));
        assertNull(enabled.automaticReviewReason(new BigDecimal("9.99")));
        for (String amount : Arrays.asList("10.00", "10.01")) {
            assertFalse(enabled.permitsAutomaticPayment(new BigDecimal(amount)));
            assertEquals("AUTO_PAY_THRESHOLD", enabled.automaticReviewReason(new BigDecimal(amount)));
        }
        config.put("autoPayEnabled", false);
        assertFalse(parse(config).permitsAutomaticPayment(new BigDecimal("0.01")));
        config.put("autoPayEnabled", true);
        config.put("autoPayBelowAmount", "0");
        assertFalse(parse(config).permitsAutomaticPayment(new BigDecimal("0.01")));
        assertEquals("AUTO_PAY_DISABLED", parse(config).automaticReviewReason(new BigDecimal("0.01")));
    }

    @Test
    public void automaticConfigurationRejectsAmbiguousSwitchesAndInvalidMoneyEvenWhileDisabled() {
        for (Object flag : Arrays.asList(null, "true", 1, "yes")) {
            JSONObject config = policy(); config.put("autoPayEnabled", flag);
            assertThrows(ServiceException.class, () -> parse(config));
        }
        for (Object cap : Arrays.asList(null, "", "-0.01", "0.001", "1000000000000", "1E+999999", "NaN")) {
            JSONObject config = new JSONObject();
            config.put("autoPayEnabled", false); config.put("autoPayBelowAmount", cap);
            assertThrows(ServiceException.class, () -> WithdrawalPolicy.normalizeAutomaticSettings(config));
        }
    }

    @Test
    public void applicationSnapshotRetainsAutomaticCapWithoutChangingLivePolicy() {
        JSONObject config = policy();
        config.put("autoPayEnabled", true); config.put("autoPayBelowAmount", "10.00");
        WithdrawalPolicy applied = parse(config);
        config.put("autoPayEnabled", false); config.put("autoPayBelowAmount", "1.00");
        assertFalse(parse(config).permitsAutomaticPayment(new BigDecimal("9.99")));
        WithdrawalPolicy saved = WithdrawalPolicy.fromSnapshot(applied.snapshot());
        assertTrue(saved.autoPayEnabled());
        assertEquals(new BigDecimal("10.00"), saved.autoPayBelowAmount());
        assertTrue(saved.permitsAutomaticPayment(new BigDecimal("9.99")));
    }
}
