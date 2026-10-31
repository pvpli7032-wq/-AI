package com.ruoyi.system.cps.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.system.cps.mapper.OrderCollectionPlanMapper;
import com.ruoyi.system.service.ISysConfigService;
import com.ruoyi.union.adapter.Platform;
import com.ruoyi.union.adapter.TimeWindow;

/** Disabled until operators supply explicit policy; no guessed business windows or ownership. */
public final class ConfiguredOrderCollectionSettings implements OrderCollectionSettings {
    public static final String POLICY_KEY = "cps.order.collection.policy";
    private final ISysConfigService configs;
    private final OrderCollectionPlanMapper plans;
    private final Clock clock;
    public ConfiguredOrderCollectionSettings(ISysConfigService configs, OrderCollectionPlanMapper plans, Clock clock) {
        this.configs = configs; this.plans = plans; this.clock = clock;
    }
    private JSONObject policy() {
        String text = configs.selectConfigByKey(POLICY_KEY);
        if (text == null || text.isBlank()) throw new IllegalStateException("订单采集策略尚未配置");
        try {
            JSONObject policy = JSON.parseObject(text);
            if (policy == null) throw new IllegalArgumentException();
            return policy;
        } catch (RuntimeException invalid) { throw new IllegalStateException("订单采集策略无效"); }
    }
    @Override public List<Work> pending(OrderCollectionRetryQueue.TaskType type) {
        JSONObject policy = policy();
        if (!policy.getBooleanValue("enabled")) throw new IllegalStateException("订单采集尚未启用");
        JSONArray feeds = policy.getJSONArray("feeds");
        if (feeds == null || feeds.isEmpty()) throw new IllegalStateException("订单平台窗口尚未配置");
        List<Work> result = new ArrayList<>();
        java.util.Set<Platform> seen = new java.util.HashSet<>();
        for (int index = 0; index < feeds.size(); index++) {
            JSONObject feed = feeds.getJSONObject(index);
            if (!type.name().equals(feed.getString("taskType"))) continue;
            Platform platform = Platform.valueOf(feed.getString("platform"));
            if (!seen.add(platform)) throw new IllegalStateException("同平台任务策略重复");
            int sourceLimit = type == OrderCollectionRetryQueue.TaskType.PENALTY ? 2592000
                : platform == Platform.JD ? 3600 : platform == Platform.PDD ? 86400 : 10800;
            int size = positive(feed, "windowSeconds", sourceLimit);
            int overlap = positive(feed, "overlapSeconds", size - 1);
            int max = positive(feed, "maxWindows", 10000);
            int lag = nonNegative(feed, "lagSeconds");
            Instant end = clock.instant().minusSeconds(lag).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            boolean daily = type == OrderCollectionRetryQueue.TaskType.BACKFILL;
            boolean hourly = type == OrderCollectionRetryQueue.TaskType.PENALTY;
            if (daily) end = end.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC);
            if (hourly) end = end.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
            String cycle = daily ? end.atOffset(ZoneOffset.UTC).toLocalDate().toString()
                : hourly ? end.toString() : "incremental";
            // Punishment is queried by order creation, not punishment modification. Rescan each hourly cycle.
            Instant initial = daily || hourly
                ? end.minusSeconds(positive(feed, "lookbackSeconds", Integer.MAX_VALUE)) : Instant.parse(feed.getString("initialFrom"));
            if (initial.getNano() != 0 || !initial.isBefore(end)) throw new IllegalStateException("采集起止时间无效");
            Map<String,Object> key = new HashMap<>();
            key.put("platform", platform.name()); key.put("type", type.name()); key.put("cycle", cycle);
            key.put("initial", utc(initial)); plans.initialize(key);
            LocalDateTime checkpoint = plans.checkpoint(key);
            if (checkpoint == null) throw new IllegalStateException("采集检查点缺失");
            Instant previous = checkpoint.toInstant(ZoneOffset.UTC);
            for (int i = 0; i < max && previous.isBefore(end); i++) {
                Instant from = previous.minusSeconds(overlap);
                Instant to = from.plusSeconds(size);
                if (to.isAfter(end)) to = end;
                UUID runId = UUID.randomUUID();
                Map<String,Object> values = new HashMap<>(key);
                values.put("runId", runId.toString()); values.put("expected", utc(previous));
                values.put("from", utc(from)); values.put("to", utc(to));
                if (plans.plan(values) != 1) throw new IllegalStateException("采集计划保存失败");
                result.add(new Work(runId, platform, type, new TimeWindow(from, to)));
                previous = to;
            }
        }
        if (seen.isEmpty()) throw new IllegalStateException("该任务没有配置平台窗口");
        return List.copyOf(result);
    }
    @Override public boolean belongsToSystem(Work work, OrderCollectionFeed.Entry entry) {
        JSONObject ownership = policy().getJSONObject("ownership");
        if (ownership == null || entry == null || entry.incoming() == null || entry.incoming().line() == null) return false;
        JSONObject platform = ownership.getJSONObject(work.platform().name());
        if (platform == null) return false;
        JSONArray pids = platform.getJSONArray("pids"), sites = platform.getJSONArray("siteIds");
        return pids != null && sites != null && entry.incoming().line().pid() != null && entry.siteId() != null
            && pids.contains(entry.incoming().line().pid()) && sites.contains(entry.siteId());
    }
    @Override public void auditRejected(Work work, OrderCollectionFeed.Entry entry) {
        if (plans.auditRejected(Map.of("runId", work.runId().toString(), "platform", work.platform().name())) != 1)
            throw new IllegalStateException("采集过滤审计保存失败");
    }
    @Override public void completed(Work work) {
        String id = work.runId().toString();
        if (plans.complete(id) != 1 && plans.alreadyCompleted(id) != 1)
            throw new IllegalStateException("采集检查点冲突，请重试");
    }
    @Override public int retryLeaseSeconds() { return positive(policy(), "retryLeaseSeconds", 3600); }
    @Override public int retryContentionAttempts() { return positive(policy(), "retryContentionAttempts", 100); }
    @Override public int retryLimit() { return positive(policy(), "retryLimit", 10000); }
    @Override public LocalDateTime nextRetryAt() { return utc(clock.instant().plusSeconds(positive(policy(), "retryDelaySeconds", Integer.MAX_VALUE))); }
    @Override public Duration outboxLease() { return Duration.ofSeconds(positive(policy(), "outboxLeaseSeconds", 3600)); }
    @Override public int outboxLimit() { return positive(policy(), "outboxLimit", 10000); }
    private static int positive(JSONObject values, String key, int max) {
        int value = nonNegative(values, key);
        if (value < 1 || value > max) throw new IllegalStateException("订单采集参数无效: " + key);
        return value;
    }
    private static int nonNegative(JSONObject values, String key) {
        Integer value = values.getInteger(key);
        if (value == null || value < 0) throw new IllegalStateException("订单采集参数缺失或无效: " + key);
        return value;
    }
    private static LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
}
