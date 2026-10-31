package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.ReconBatchMapper;

@Service
public class ExposureService {
    public static final String LIMIT_KEY = "cps.exposure.limit";
    public static final String ACTION_KEY = "cps.exposure.action";
    public static final String INTERVAL_KEY = "cps.exposure.intervalMinutes";
    private static final int DEFAULT_INTERVAL_MINUTES = 60;
    private final ReconBatchMapper mapper;
    private final Clock clock;

    @Autowired
    public ExposureService(ReconBatchMapper mapper) { this(mapper, Clock.systemUTC()); }

    public ExposureService(ReconBatchMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock.withZone(ZoneOffset.UTC);
    }

    /** Read at both application and dispatch; an application transfers an already counted liability. */
    @Transactional(readOnly = true)
    public ExposureDecision evaluateWithdrawal(BigDecimal requestedAmount) {
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("提现金额必须大于零");
        }
        BigDecimal current = total(mapper.selectExposurePositions(null)).add(mapper.selectUnallocatedUserDebt());
        // One statement observes one policy revision; scheduler settings never gate this calculation.
        List<ExposureConfigValue> values = mapper.selectExposureConfigValues();
        BigDecimal limit;
        ExposureLimitAction action;
        try {
            String configured = value(values, LIMIT_KEY, null);
            if (configured == null || configured.isBlank()) {
                throw new ServiceException("敞口上限尚未配置，待财务确认");
            }
            limit = limit(configured);
            action = action(values);
        } catch (ServiceException ex) {
            return new ExposureDecision(true, true, ex.getMessage(), current, null);
        }
        if (limit.signum() == 0 || current.compareTo(limit) < 0) {
            return new ExposureDecision(false, false, limit.signum() == 0 ? "敞口上限已关闭" : "通过", current, limit);
        }
        boolean blocked = action == ExposureLimitAction.REJECT;
        return new ExposureDecision(blocked, true,
            blocked ? "当前敞口达到或超过上限，禁止提现" : "当前敞口达到或超过上限，需人工审核", current, limit);
    }

    @Transactional(readOnly = true)
    public ExposurePolicy configuration() {
        List<ExposureConfigValue> values = mapper.selectExposureConfigValues();
        String configured = value(values, LIMIT_KEY, null);
        return new ExposurePolicy(configured == null || configured.isBlank() ? null : limit(configured),
            action(values), interval(values));
    }

    /** Validated values and their audit record commit together; readers always query the database. */
    @Transactional
    public ExposurePolicy configure(ExposurePolicy requested, long operatorId) {
        if (operatorId <= 0) throw new ServiceException("操作人不能为空");
        if (requested == null || requested.limit() == null) throw new ServiceException("敞口上限不能为空，填0关闭");
        if (requested.action() == null) throw new ServiceException("敞口到限动作不能为空");
        if (requested.intervalMinutes() <= 0) throw new ServiceException("敞口计算间隔必须为正整数分钟");
        ExposurePolicy policy = new ExposurePolicy(validLimit(requested.limit()), requested.action(), requested.intervalMinutes());
        lockSchedule();
        List<ExposureConfigValue> before = mapper.selectExposureConfigValues();
        save(before, LIMIT_KEY, "垫付敞口上限", policy.limit().toPlainString(), operatorId);
        save(before, ACTION_KEY, "敞口到限动作", policy.action().name(), operatorId);
        save(before, INTERVAL_KEY, "敞口定时计算间隔（分钟）", Integer.toString(policy.intervalMinutes()), operatorId);
        mapper.appendExposurePolicyAudit(operatorId, JSON.toJSONString(before), JSON.toJSONString(policy));
        return policy;
    }

    @Transactional(readOnly = true)
    public ExposureSummary current() { return summary(false); }

    /** Append-only operational snapshots; peak means the greatest observed exposure. */
    @Transactional
    public ExposureSummary capture() { return summary(true); }

    /** A short Quartz tick reads the current interval; a row lock coordinates all instances. */
    @Transactional
    public boolean captureIfDue() {
        ExposureSchedule schedule = lockSchedule();
        int intervalMinutes = interval(mapper.selectExposureConfigValues());
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        if (schedule.lastCaptureAt() != null && now.isBefore(schedule.lastCaptureAt().plusMinutes(intervalMinutes))) {
            return false;
        }
        // Snapshot rows and checkpoint share this transaction. Failure leaves the tick retryable.
        summary(true);
        mapper.completeExposureCapture(now);
        return true;
    }

    @Transactional(readOnly = true)
    public List<ExposurePosition> positions() { return mapper.selectExposurePositions(null); }

    private ExposureSummary summary(boolean persist) {
        Map<String, BigDecimal> byPlatform = new TreeMap<>();
        for (String platform : List.of("tbk", "jd", "pdd", "UNKNOWN")) byPlatform.put(platform, BigDecimal.ZERO);
        for (ExposurePosition p : mapper.selectExposurePositions(null)) {
            byPlatform.merge(p.platform(), p.exposure(), BigDecimal::add);
        }
        BigDecimal known = money(byPlatform.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal debt = money(mapper.selectUnallocatedUserDebt());
        byPlatform.put("UNALLOCATED_USER_DEBT", debt);
        List<ExposurePlatform> platforms = new ArrayList<>();
        BigDecimal current = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> p : byPlatform.entrySet()) {
            BigDecimal amount = money(p.getValue());
            current = current.add(amount);
            if (persist) mapper.appendExposure(p.getKey(), amount);
            platforms.add(new ExposurePlatform(p.getKey(), amount, amount.max(mapper.peakExposure(p.getKey()))));
        }
        current = money(current);
        if (persist) mapper.appendExposure("ALL", current);
        return new ExposureSummary(current, current.max(mapper.peakExposure("ALL")), known, debt, List.copyOf(platforms), clock.instant());
    }

    private ExposureSchedule lockSchedule() {
        ExposureSchedule schedule = mapper.lockExposureSchedule();
        if (schedule == null) throw new ServiceException("敞口调度状态缺失，请检查数据库迁移");
        return schedule;
    }

    private void save(List<ExposureConfigValue> before, String key, String name, String configured, long operatorId) {
        long existing = before.stream().filter(row -> key.equals(row.key())).count();
        if (existing > 1) throw new ServiceException("敞口配置重复，请先处理：" + key);
        if (existing == 0) mapper.insertExposureConfig(key, name, configured, operatorId);
        else mapper.updateExposureConfig(key, configured, operatorId);
    }

    private static String value(List<ExposureConfigValue> values, String key, String defaultValue) {
        List<ExposureConfigValue> matches = values.stream().filter(row -> key.equals(row.key())).toList();
        if (matches.size() > 1) throw new ServiceException("敞口配置重复，请先处理：" + key);
        return matches.isEmpty() ? defaultValue : matches.get(0).value();
    }

    private static BigDecimal limit(String configured) {
        try { return validLimit(new BigDecimal(configured)); }
        catch (NumberFormatException ex) { throw new ServiceException("敞口上限配置无效"); }
    }

    private static BigDecimal validLimit(BigDecimal limit) {
        try {
            if (limit.signum() < 0 || limit.precision() > 18 || limit.scale() < -16 || limit.scale() > 18) {
                throw new ServiceException("敞口上限必须是非负人民币金额，最多16位整数和2位小数");
            }
            BigDecimal normalized = limit.setScale(2, RoundingMode.UNNECESSARY);
            if (normalized.precision() > 18) throw new ServiceException("敞口上限超出有效范围");
            return normalized;
        } catch (ArithmeticException ex) { throw new ServiceException("敞口上限最多保留2位小数"); }
    }

    private static ExposureLimitAction action(List<ExposureConfigValue> values) {
        String action = value(values, ACTION_KEY, ExposureLimitAction.MANUAL_REVIEW.name());
        try { return ExposureLimitAction.valueOf(action == null ? "" : action); }
        catch (IllegalArgumentException ex) { throw new ServiceException("敞口到限动作配置无效"); }
    }

    private static int interval(List<ExposureConfigValue> values) {
        String configured = value(values, INTERVAL_KEY, Integer.toString(DEFAULT_INTERVAL_MINUTES));
        try {
            int interval = Integer.parseInt(configured);
            if (interval > 0) return interval;
        } catch (NumberFormatException ex) { /* Keep invalid configuration visible instead of silently changing it. */ }
        throw new ServiceException("敞口计算间隔必须为正整数分钟");
    }

    private static BigDecimal total(List<ExposurePosition> positions) {
        return money(positions.stream().map(ExposurePosition::exposure).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }
}
