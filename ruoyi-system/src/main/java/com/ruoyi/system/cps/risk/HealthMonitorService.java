package com.ruoyi.system.cps.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysConfigService;

/** Durable operational alerts. Authorization expiry has no disable switch. */
@Service
public class HealthMonitorService {
    public static final Duration AUTH_REPEAT = Duration.ofMinutes(10);
    public static final String WITHDRAWAL_RESULT_CONFLICT = "WITHDRAWAL_RESULT_CONFLICT";
    private final JdbcTemplate jdbc;
    private final RiskPolicy policy;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public HealthMonitorService(JdbcTemplate jdbc, ISysConfigService configs, PlatformTransactionManager manager) {
        this(jdbc, configs, manager, Clock.systemDefaultZone());
    }

    public HealthMonitorService(JdbcTemplate jdbc, ISysConfigService configs,
                                PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc;
        this.policy = new RiskPolicy(configs);
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Transactional
    public void record(HealthObservation observation, long operator) {
        RiskService.requireUserId(operator);
        if (observation == null || observation.kind() == null) throw new ServiceException("健康证据类型不能为空");
        RiskService.requireText(observation.eventKey(), 128, "事件标识");
        if (observation.subject() == null || !observation.subject().matches("[A-Za-z0-9_.:/-]{1,128}")) {
            throw new ServiceException("健康证据对象应为平台或通道标识，不得包含密钥或个人资料");
        }
        if (observation.observedAt() == null || observation.observedAt().isAfter(LocalDateTime.now(clock))) {
            throw new ServiceException("健康证据时间无效");
        }
        LocalDateTime observedAt = observation.observedAt().withNano(observation.observedAt().getNano() / 1_000_000 * 1_000_000);
        BigDecimal balance = observation.balance();
        switch (observation.kind()) {
            case AUTHORIZATION -> {
                if (observation.authorized() == null) throw new ServiceException("授权状态不能为空");
            }
            case CHANNEL_BALANCE -> {
                if (balance == null || balance.signum() < 0 || balance.precision() > 14
                    || balance.scale() < -12 || balance.scale() > 12) {
                    throw new ServiceException("通道余额无效");
                }
                balance = balance.setScale(2, RoundingMode.HALF_UP);
                if (balance.precision() > 14) throw new ServiceException("通道余额超出有效范围");
            }
            case REFUND_RATE -> {
                validCounts(observation.currentOrders(), observation.currentRefunds());
                validCounts(observation.baselineOrders(), observation.baselineRefunds());
            }
        }
        String payloadHash = RiskService.hash(observation.kind() + "|" + observation.subject() + "|"
            + observedAt + "|"
            + observation.authorized() + "|" + balance + "|" + observation.currentOrders() + "|"
            + observation.currentRefunds() + "|" + observation.baselineOrders() + "|" + observation.baselineRefunds());
        int inserted = jdbc.update("INSERT IGNORE INTO cps_health_observation(event_key,payload_hash,kind,subject,observed_at,authorized,"
            + "balance,current_orders,current_refunds,baseline_orders,baseline_refunds,operator_id,created_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", observation.eventKey(), payloadHash, observation.kind().name(),
            observation.subject(), observedAt, observation.authorized(), balance,
            observation.currentOrders(), observation.currentRefunds(), observation.baselineOrders(), observation.baselineRefunds(),
            operator, LocalDateTime.now(clock));
        if (inserted == 0 && !payloadHash.equals(jdbc.queryForObject(
            "SELECT payload_hash FROM cps_health_observation WHERE event_key=?", String.class, observation.eventKey()))) {
            throw new ServiceException("事件标识已用于不同的健康证据");
        }
    }

    private static void validCounts(Long orders, Long refunds) {
        if (orders == null || refunds == null || orders <= 0 || refunds < 0 || refunds > orders) {
            throw new ServiceException("退款样本笔数无效");
        }
    }

    /** Each alert is committed separately, so a bad optional threshold cannot suppress auth alerts. */
    public int check() {
        // Match DATETIME(3) exactly. Database rounding of a finer Instant can move
        // last_emitted_at forward and incorrectly suppress the exact 10-minute retry.
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
        int emitted = 0;
        Set<String> invalidAuthorizations = new HashSet<>();
        for (Long platform : jdbc.queryForList("SELECT id FROM sys_union_platform WHERE status='0' AND is_quote='2'", Long.class)) {
            invalidAuthorizations.add("platform:" + platform);
        }
        List<Map<String, Object>> latest = jdbc.queryForList("SELECT o.* FROM cps_health_observation o WHERE NOT EXISTS "
            + "(SELECT 1 FROM cps_health_observation n WHERE n.kind=o.kind AND n.subject=o.subject "
            + "AND (n.observed_at>o.observed_at OR (n.observed_at=o.observed_at AND n.id>o.id)))");
        for (Map<String, Object> observation : latest) {
            if ("AUTHORIZATION".equals(observation.get("kind")) && !RiskService.flag(observation.get("authorized"))) {
                invalidAuthorizations.add((String) observation.get("subject"));
            }
        }
        for (String subject : invalidAuthorizations) {
            emitted += emit("AUTHORIZATION_EXPIRED", subject, "联盟授权失效，请重新授权并补采在途订单", now);
        }
        for (String subject : jdbc.queryForList("SELECT subject FROM cps_health_alert_state "
            + "WHERE kind='AUTHORIZATION_EXPIRED' AND condition_active=1", String.class)) {
            if (!invalidAuthorizations.contains(subject)) clear("AUTHORIZATION_EXPIRED", subject);
        }
        BigDecimal minimumBalance = configured("cps.health.channel.minimumBalance", now);
        BigDecimal refundMultiplier = configured("cps.health.refund.rateMultiplier", now);
        BigDecimal minimumOrders = configured("cps.health.refund.minimumOrders", now);
        for (Map<String, Object> observation : latest) {
            String subject = (String) observation.get("subject");
            if ("CHANNEL_BALANCE".equals(observation.get("kind")) && minimumBalance != null) {
                if (minimumBalance.signum() > 0 && ((BigDecimal) observation.get("balance")).compareTo(minimumBalance) < 0) {
                    emitted += emit("CHANNEL_BALANCE_LOW", subject, "通道预付余额低于后台配置门槛", now);
                } else clear("CHANNEL_BALANCE_LOW", subject);
            }
            if ("REFUND_RATE".equals(observation.get("kind")) && refundMultiplier != null && minimumOrders != null) {
                BigDecimal currentOrders = new BigDecimal(observation.get("current_orders").toString());
                BigDecimal baselineOrders = new BigDecimal(observation.get("baseline_orders").toString());
                BigDecimal currentRefunds = new BigDecimal(observation.get("current_refunds").toString());
                BigDecimal baselineRefunds = new BigDecimal(observation.get("baseline_refunds").toString());
                boolean enabled = refundMultiplier.signum() > 0 && minimumOrders.signum() > 0;
                // Cross multiplication avoids rounding a ratio close to its alert boundary.
                BigDecimal currentCompared = currentRefunds.multiply(baselineOrders);
                BigDecimal baselineCompared = baselineRefunds.multiply(currentOrders);
                boolean increase = currentCompared.compareTo(baselineCompared) > 0
                    && currentCompared.compareTo(baselineCompared.multiply(refundMultiplier)) >= 0;
                if (enabled && currentOrders.compareTo(minimumOrders) >= 0 && baselineOrders.compareTo(minimumOrders) >= 0 && increase) {
                    emitted += emit("REFUND_RATE_SPIKE", subject, "退款率相对基线达到后台配置告警倍数", now);
                } else clear("REFUND_RATE_SPIKE", subject);
            }
        }
        return emitted;
    }

    private BigDecimal configured(String key, LocalDateTime now) {
        try {
            BigDecimal value = policy.decimal(key);
            clear("CONFIGURATION_INVALID", key);
            return value;
        } catch (ServiceException ex) {
            emit("CONFIGURATION_INVALID", key, "健康检查配置缺失或无效，授权失效检查继续运行", now);
            return null;
        }
    }

    /** A contradictory terminal result stays visible without emitting again for callback retries. */
    public int recordWithdrawalResultConflict(String withdrawNo, String localStatus, String channelStatus) {
        RiskService.requireText(withdrawNo, 128, "提现号");
        RiskService.requireText(localStatus, 32, "提现状态");
        RiskService.requireText(channelStatus, 32, "通道状态");
        return emit(WITHDRAWAL_RESULT_CONFLICT, withdrawNo,
            "提现终态矛盾；本地=" + localStatus + "，通道=" + channelStatus + "；保留原终态与原账务，必须人工对账",
            LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS));
    }

    private int emit(String kind, String subject, String message, LocalDateTime now) {
        Integer result = transactions.execute(status -> {
            // Upsert acquires an exclusive row lock immediately; INSERT IGNORE followed by
            // FOR UPDATE can deadlock when concurrent duplicate inserts upgrade shared locks.
            jdbc.update("INSERT INTO cps_health_alert_state(kind,subject,condition_active) VALUES (?,?,0) "
                + "ON DUPLICATE KEY UPDATE kind=VALUES(kind)", kind, subject);
            Map<String, Object> previous = jdbc.queryForMap(
                "SELECT condition_active,last_emitted_at FROM cps_health_alert_state WHERE kind=? AND subject=? FOR UPDATE", kind, subject);
            Object last = previous.get("last_emitted_at");
            if (RiskService.flag(previous.get("condition_active"))
                && (WITHDRAWAL_RESULT_CONFLICT.equals(kind)
                    || (last != null && RiskService.toTime(last).plus(AUTH_REPEAT).isAfter(now)))) return 0;
            jdbc.update("INSERT INTO cps_health_alert(kind,subject,message,created_at) VALUES (?,?,?,?)", kind, subject, message, now);
            jdbc.update("UPDATE cps_health_alert_state SET condition_active=1,last_emitted_at=? WHERE kind=? AND subject=?", now, kind, subject);
            return 1;
        });
        return result == null ? 0 : result;
    }

    private void clear(String kind, String subject) {
        jdbc.update("UPDATE cps_health_alert_state SET condition_active=0 WHERE kind=? AND subject=? AND condition_active=1", kind, subject);
    }

    public List<Map<String, Object>> alerts() {
        return jdbc.queryForList("SELECT a.*,s.condition_active FROM cps_health_alert a JOIN cps_health_alert_state s "
            + "ON a.kind=s.kind AND a.subject=s.subject ORDER BY a.id DESC LIMIT 200");
    }
}
