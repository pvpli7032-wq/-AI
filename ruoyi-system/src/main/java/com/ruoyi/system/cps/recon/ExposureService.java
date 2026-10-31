package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.cps.mapper.ReconBatchMapper;

@Service
public class ExposureService {
    private final ReconBatchMapper mapper;

    public ExposureService(ReconBatchMapper mapper) { this.mapper = mapper; }

    /** Read at both application and dispatch; an application transfers an already counted liability. */
    @Transactional(readOnly = true)
    public ExposureDecision evaluateWithdrawal(BigDecimal requestedAmount) {
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("提现金额必须大于零");
        }
        BigDecimal current = total(mapper.selectExposurePositions(null)).add(mapper.selectUnallocatedUserDebt());
        List<String> values = mapper.selectConfigValues("cps.exposure.limit");
        BigDecimal limit;
        try {
            if (values.size() != 1 || values.get(0) == null || values.get(0).isBlank()) {
                return new ExposureDecision(true, true, "敞口上限尚未配置，待财务确认", current, null);
            }
            limit = new BigDecimal(values.get(0)).setScale(2, RoundingMode.UNNECESSARY);
            if (limit.signum() <= 0) {
                return new ExposureDecision(true, true, "敞口上限配置无效", current, null);
            }
        } catch (NumberFormatException | ArithmeticException ex) {
            return new ExposureDecision(true, true, "敞口上限配置无效", current, null);
        }
        int comparison = current.compareTo(limit);
        return new ExposureDecision(comparison > 0, comparison >= 0,
            comparison > 0 ? "当前垫付敞口超过上限" : comparison == 0 ? "当前敞口达到上限，需人工审核" : "通过",
            current, limit);
    }

    @Transactional(readOnly = true)
    public ExposureSummary current() { return summary(false); }

    /** Append-only daily/operational snapshots; peak means the greatest observed exposure. */
    @Transactional
    public ExposureSummary capture() { return summary(true); }

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
        return new ExposureSummary(current, current.max(mapper.peakExposure("ALL")), known, debt, List.copyOf(platforms), Instant.now());
    }

    private static BigDecimal total(List<ExposurePosition> positions) {
        return money(positions.stream().map(ExposurePosition::exposure).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }
}
