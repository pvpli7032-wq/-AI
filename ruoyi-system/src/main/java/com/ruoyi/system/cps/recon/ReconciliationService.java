package com.ruoyi.system.cps.recon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.cps.mapper.ReconBatchMapper;
import com.ruoyi.system.cps.mapper.ReconDiffMapper;

/** Four source boundaries, without equating gross commission to the user's net payout. */
@Service
public class ReconciliationService {
    private static final Set<String> PLATFORMS = Set.of("tbk", "jd", "pdd", "PAYOUT");
    private static final Set<String> SOURCES = Set.of("UNION_REPORT", "UNION_RECEIPT", "PAYOUT");
    private final ReconBatchMapper batches;
    private final ReconDiffMapper diffs;

    public ReconciliationService(ReconBatchMapper batches, ReconDiffMapper diffs) {
        this.batches = batches;
        this.diffs = diffs;
    }

    /** Re-imports must match exactly. Corrections are new source deltas, never overwrites. */
    @Transactional
    public ReconSource importSource(ReconImportRequest request, Long operatorId) {
        requireOperator(operatorId);
        ReconImportRequest normalized = validate(request);
        batches.lockImportGuard();
        ReconSource existing = batches.findSource(normalized.platform(), normalized.sourceType(), normalized.externalId());
        if (existing != null) {
            if (!same(existing, normalized)) throw new ServiceException("同一来源流水号的内容不一致，禁止覆盖");
            return existing;
        }
        if (!"UNION_REPORT".equals(normalized.sourceType())
            && batches.findCashSource(normalized.sourceType(), normalized.externalId()) != null) {
            throw new ServiceException("同一现金证据行号不能跨平台重复导入，请使用带来源账户命名空间的唯一证据行号");
        }
        batches.insertSource(normalized, operatorId);
        return batches.findSource(normalized.platform(), normalized.sourceType(), normalized.externalId());
    }

    /** An explicit finance allocation; unallocated bank cash never silently removes exposure. */
    @Transactional
    public ReconCoverage allocateReceipt(ReconCoverageRequest request, Long operatorId) {
        requireOperator(operatorId);
        if (request == null) throw new ServiceException("到账覆盖分配不能为空");
        text(request.allocationNo(), 128, "分配号");
        text(request.reason(), 500, "财务确认说明");
        BigDecimal amount = money(request.amount(), 2);
        if (request.receiptId() == null || amount.signum() <= 0) throw new ServiceException("到账覆盖金额必须大于零");
        ReconCoverageRequest normalized = new ReconCoverageRequest(request.allocationNo(), request.receiptId(), amount, request.reason());
        batches.lockImportGuard();
        ReconCoverage existing = batches.findCoverage(request.allocationNo());
        if (existing != null) {
            if (!existing.receiptId().equals(request.receiptId()) || existing.amount().compareTo(amount) != 0
                || !existing.reason().equals(request.reason())) throw new ServiceException("分配号已被不同的到账覆盖使用");
            return existing;
        }
        ReconSource receipt = batches.findSourceById(request.receiptId());
        if (receipt == null || !"UNION_RECEIPT".equals(receipt.sourceType()) || receipt.amount().signum() <= 0) {
            throw new ServiceException("只能分配真实正向联盟到账记录");
        }
        BigDecimal used = batches.coveredForReceipt(receipt.id());
        if (used.add(amount).compareTo(receipt.amount()) > 0) throw new ServiceException("覆盖金额超过该笔实际到账可分配额");
        ExposurePosition position = batches.selectExposurePositions(receipt.platform()).stream()
            .filter(p -> p.orderNo().equals(receipt.orderNo()) && p.subOrderNo().equals(receipt.subOrderNo()))
            .findFirst().orElseThrow(() -> new ServiceException("订单没有可覆盖的已入账净负债"));
        if (amount.compareTo(position.exposure()) > 0 || amount.compareTo(position.unallocatedCash()) > 0) {
            throw new ServiceException("覆盖金额超过订单未覆盖负债或净到账金额");
        }
        batches.insertCoverage(normalized, operatorId);
        return batches.findCoverage(request.allocationNo());
    }

    @Transactional
    public ReconBatch reconcile(String platform, String period) {
        requirePlatform(platform);
        YearMonth month = month(period);
        // Serialize evidence imports with snapshot creation; the same evidence produces one batch.
        batches.lockImportGuard();
        List<ReconSource> sources = batches.selectSources(platform, month.atDay(1), month.plusMonths(1).atDay(1));
        List<ReconOrder> orders = "PAYOUT".equals(platform) ? List.of()
            : batches.selectOrders(platform, month.atDay(1), month.plusMonths(1).atDay(1));
        List<ReconPayout> postings = "PAYOUT".equals(platform)
            ? batches.selectPayoutPostings(month.atDay(1), month.plusMonths(1).atDay(1)) : List.of();
        List<ReconPayout> withdrawals = "PAYOUT".equals(platform)
            ? batches.selectSuccessfulWithdrawals(month.atDay(1), month.plusMonths(1).atDay(1)) : List.of();
        Set<String> cohort = new HashSet<>();
        for (ReconSource source : sources) cohort.add(orderKey(source.orderNo(), source.subOrderNo()));
        for (ReconOrder order : orders) cohort.add(orderKey(order.orderNo(), order.subOrderNo()));
        List<ExposurePosition> positions = "PAYOUT".equals(platform) ? List.of() : batches.selectExposurePositions(platform).stream()
            .filter(position -> cohort.contains(orderKey(position.orderNo(), position.subOrderNo()))).toList();
        List<Difference> differences = "PAYOUT".equals(platform)
            ? comparePayouts(sources, withdrawals, postings) : compareUnion(sources, orders, positions);
        String fingerprint = digest(platform + "\n" + period + "\n" + sources + "\n" + orders + "\n"
            + postings + "\n" + withdrawals + "\n" + positions);
        batches.insertBatch(platform, period, fingerprint);
        ReconBatch batch = batches.lockBatch(fingerprint);
        if ("COMPLETED".equals(batch.status())) return batch;
        for (Difference difference : differences) {
            diffs.insert(batch.id(), difference.type(), difference.refId(), difference.amount(), difference.detail());
        }
        if (batches.completeBatch(batch.id(), differences.size()) != 1) throw new ServiceException("对账批次状态已变化");
        return batches.lockBatch(fingerprint);
    }

    @Transactional(readOnly = true)
    public List<ReconBatch> batches(String platform, String period) {
        if (platform != null) requirePlatform(platform);
        if (period != null) month(period);
        return batches.selectBatches(platform, period);
    }

    /** Review the immutable evidence belonging to this period's affected order cohort. */
    @Transactional(readOnly = true)
    public List<ReconSource> sources(String platform, String period) {
        requirePlatform(platform);
        YearMonth month = month(period);
        return batches.selectSources(platform, month.atDay(1), month.plusMonths(1).atDay(1));
    }

    @Transactional(readOnly = true)
    public List<ReconDiff> differences(Long batchId) {
        if (batchId == null || batchId <= 0) throw new ServiceException("对账批次无效");
        return diffs.selectByBatch(batchId);
    }

    /** Closing an investigation never changes evidence, ledger entries, or balances. */
    @Transactional
    public ReconDiff resolve(Long id, String note, Long operatorId) {
        requireOperator(operatorId);
        text(note, 500, "处理说明");
        ReconDiff difference = diffs.lockById(id);
        if (difference == null) throw new ServiceException("对账差异不存在");
        if ("RESOLVED".equals(difference.status())) {
            if (!Objects.equals(note, difference.resolveNote())) throw new ServiceException("差异已处理，不能覆盖处理记录");
            return difference;
        }
        diffs.resolve(id, operatorId, note);
        return diffs.lockById(id);
    }

    private List<Difference> compareUnion(List<ReconSource> sources, List<ReconOrder> orders, List<ExposurePosition> positions) {
        Map<String, BigDecimal> reported = new TreeMap<>(), expectedCash = new TreeMap<>(), received = new TreeMap<>(), recorded = new TreeMap<>();
        for (ReconSource source : sources) {
            String key = orderKey(source.orderNo(), source.subOrderNo());
            if ("UNION_REPORT".equals(source.sourceType())) {
                reported.merge(key, source.amount(), BigDecimal::add);
                expectedCash.merge(key, source.expectedReceiptAmount(), BigDecimal::add);
            } else if ("UNION_RECEIPT".equals(source.sourceType())) received.merge(key, source.amount(), BigDecimal::add);
        }
        for (ReconOrder order : orders) recorded.put(orderKey(order.orderNo(), order.subOrderNo()), order.commission());
        List<Difference> differences = new ArrayList<>();
        compare(differences, "REPORT_ORDER", "联盟报表佣金与系统订单实际佣金", reported, recorded, 4);
        compare(differences, "RECEIPT_REPORT", "联盟实际到账与报表净应到账", expectedCash, received, 2);
        for (ExposurePosition position : positions) {
            if (position.unallocatedCash().signum() > 0 && position.exposure().signum() > 0) {
                differences.add(new Difference("COVERAGE_PENDING", digest(orderKey(position.orderNo(), position.subOrderNo())),
                    position.exposure().min(position.unallocatedCash()), "已到账但缺少财务确认的用户负债覆盖分配；敞口不自动减少"));
            }
        }
        return differences;
    }

    private List<Difference> comparePayouts(List<ReconSource> sources, List<ReconPayout> withdrawals, List<ReconPayout> postings) {
        Map<String, BigDecimal> channel = new TreeMap<>(), paid = new TreeMap<>(), ledger = new TreeMap<>();
        for (ReconSource source : sources) channel.merge(source.withdrawNo(), source.amount(), BigDecimal::add);
        for (ReconPayout withdrawal : withdrawals) paid.merge(withdrawal.withdrawNo(), withdrawal.amount(), BigDecimal::add);
        for (ReconPayout posting : postings) ledger.merge(posting.withdrawNo(), posting.amount(), BigDecimal::add);
        List<Difference> differences = new ArrayList<>();
        compare(differences, "PAYOUT_WITHDRAWAL", "通道实际打款与成功提现单", paid, channel, 2);
        compare(differences, "PAYOUT_LEDGER", "成功提现单与台账打款出账", paid, ledger, 2);
        return differences;
    }

    private static void compare(List<Difference> output, String type, String label,
        Map<String, BigDecimal> expected, Map<String, BigDecimal> actual, int scale) {
        Set<String> keys = new TreeSet<>(expected.keySet());
        keys.addAll(actual.keySet());
        for (String key : keys) {
            BigDecimal left = expected.getOrDefault(key, BigDecimal.ZERO).setScale(scale, RoundingMode.HALF_UP);
            BigDecimal right = actual.getOrDefault(key, BigDecimal.ZERO).setScale(scale, RoundingMode.HALF_UP);
            if (left.compareTo(right) != 0 || !expected.containsKey(key) || !actual.containsKey(key)) {
                String detail = label + "；关联=" + key + "；应有=" + left.toPlainString() + "；实际=" + right.toPlainString()
                    + (!expected.containsKey(key) ? "；缺少应有记录" : "") + (!actual.containsKey(key) ? "；缺少实际记录" : "");
                output.add(new Difference(type, digest(key), money(right.subtract(left), 2), detail));
            }
        }
    }

    private static ReconImportRequest validate(ReconImportRequest r) {
        if (r == null) throw new ServiceException("对账来源不能为空");
        requirePlatform(r.platform());
        if (r.sourceType() == null || !SOURCES.contains(r.sourceType())) throw new ServiceException("对账来源类型无效");
        text(r.externalId(), 128, "来源流水号");
        if (r.occurredOn() == null) throw new ServiceException("业务日期不能为空");
        boolean payout = "PAYOUT".equals(r.sourceType());
        if (payout != "PAYOUT".equals(r.platform())) throw new ServiceException("打款使用全局 PAYOUT 维度，不能伪造平台归属");
        String orderNo = r.orderNo() == null ? "" : r.orderNo();
        String subOrderNo = r.subOrderNo() == null ? "" : r.subOrderNo();
        String withdrawNo = r.withdrawNo() == null ? "" : r.withdrawNo();
        if (payout) {
            text(withdrawNo, 100, "提现号");
            if (!orderNo.isEmpty() || !subOrderNo.isEmpty()) throw new ServiceException("打款证据不接受订单归属");
        } else {
            text(orderNo, 100, "订单号");
            if (subOrderNo.length() > 100 || !withdrawNo.isEmpty()) throw new ServiceException("订单来源字段无效");
        }
        boolean report = "UNION_REPORT".equals(r.sourceType());
        BigDecimal amount = money(r.amount(), report ? 4 : 2);
        BigDecimal expected = report ? money(r.expectedReceiptAmount(), 2) : null;
        if (!report && r.expectedReceiptAmount() != null) throw new ServiceException("仅联盟报表可提供净应到账金额");
        return new ReconImportRequest(r.platform(), r.sourceType(), r.externalId(), orderNo, subOrderNo, withdrawNo, amount, expected, r.occurredOn());
    }

    private static boolean same(ReconSource a, ReconImportRequest b) {
        return Objects.equals(a.orderNo(), b.orderNo()) && Objects.equals(a.subOrderNo(), b.subOrderNo())
            && Objects.equals(a.withdrawNo(), b.withdrawNo()) && a.amount().compareTo(b.amount()) == 0
            && (a.expectedReceiptAmount() == null ? b.expectedReceiptAmount() == null
                : b.expectedReceiptAmount() != null && a.expectedReceiptAmount().compareTo(b.expectedReceiptAmount()) == 0)
            && a.occurredOn().equals(b.occurredOn());
    }

    private static BigDecimal money(BigDecimal value, int scale) {
        if (value == null) throw new ServiceException("金额不能为空");
        BigDecimal rounded = value.setScale(scale, RoundingMode.HALF_UP);
        if (rounded.abs().compareTo(new BigDecimal("999999999999.99")) > 0) throw new ServiceException("金额超出存储范围");
        return rounded;
    }

    private static void requireOperator(Long id) { if (id == null || id <= 0) throw new ServiceException("缺少登录操作人"); }
    private static void requirePlatform(String platform) {
        if (platform == null || !PLATFORMS.contains(platform)) throw new ServiceException("平台必须为 tbk、jd、pdd 或 PAYOUT");
    }
    private static YearMonth month(String period) {
        try {
            if (period == null || !period.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
            return YearMonth.parse(period);
        } catch (RuntimeException ex) { throw new ServiceException("对账期间必须为 YYYY-MM"); }
    }
    private static void text(String value, int max, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > max
            || value.chars().anyMatch(Character::isISOControl)) throw new ServiceException(name + "无效");
    }
    private static String orderKey(String orderNo, String subOrderNo) { return orderNo.length() + ":" + orderNo + ":" + subOrderNo; }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private record Difference(String type, String refId, BigDecimal amount, String detail) {}
}
