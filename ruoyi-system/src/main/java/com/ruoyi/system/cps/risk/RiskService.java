package com.ruoyi.system.cps.risk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.system.service.ISysConfigService;

/**
 * Persists abuse evidence and the manual-review hold. Only the ledger may change money.
 * A settlement-side reward-release contract is required to consume rewardsPaused;
 * this class deliberately does not modify another line's settlement rows or interfaces.
 */
@Service
public class RiskService {
    private final JdbcTemplate jdbc;
    private final RiskPolicy policy;

    public RiskService(JdbcTemplate jdbc, ISysConfigService configs) {
        this.jdbc = jdbc;
        this.policy = new RiskPolicy(configs);
    }

    @Transactional
    public RiskDecision evaluateWithdrawal(long userId) {
        requireUserId(userId);
        List<Map<String, Object>> users = jdbc.queryForList(
            "SELECT status,del_flag,risk_level FROM sys_user WHERE user_id=? FOR UPDATE", userId);
        List<String> reasons = new ArrayList<>();
        boolean blocked = users.isEmpty();
        if (blocked) {
            reasons.add("USER_NOT_FOUND");
        } else {
            // Materialize and lock even an untouched user's guard. Under READ COMMITTED,
            // SELECT FOR UPDATE of a missing row alone would not serialize a concurrent ban.
            jdbc.update("INSERT INTO cps_risk_user_state(user_id,withdrawal_blocked,rewards_paused,manual_review,updated_at) "
                + "VALUES (?,0,0,0,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id)", userId);
            Map<String, Object> user = users.getFirst();
            blocked = !"0".equals(user.get("status")) || !"0".equals(user.get("del_flag"));
            if (blocked) reasons.add("USER_DISABLED");
            String level = String.valueOf(user.get("risk_level"));
            if ("BANNED".equalsIgnoreCase(level) || "BLOCKED".equalsIgnoreCase(level)) {
                blocked = true;
                reasons.add("USER_BANNED");
            }
        }
        List<Map<String, Object>> states = jdbc.queryForList(
            "SELECT withdrawal_blocked,rewards_paused,manual_review FROM cps_risk_user_state WHERE user_id=? FOR UPDATE", userId);
        boolean paused = false;
        boolean review = false;
        if (!states.isEmpty()) {
            Map<String, Object> state = states.getFirst();
            if (flag(state.get("withdrawal_blocked"))) {
                blocked = true;
                reasons.add("WITHDRAWAL_BLOCKED");
            }
            paused = flag(state.get("rewards_paused"));
            review = flag(state.get("manual_review"));
            if (paused) reasons.add("REWARDS_PAUSED");
            if (review) reasons.add("RISK_MANUAL_REVIEW");
        }
        return new RiskDecision(blocked, review, paused, reasons);
    }

    @Transactional
    public boolean rewardsPaused(long userId) {
        return evaluateWithdrawal(userId).rewardsPaused();
    }

    /** Only a trusted server/admin importer may supply a hash; a phone model is not a device ID. */
    @Transactional
    public void recordDeviceRegistration(long userId, String deviceHash, String eventKey,
                                         LocalDateTime observedAt, long operator) {
        requireUserId(userId);
        requireUserId(operator);
        requireText(eventKey, 128, "事件标识");
        if (deviceHash == null || !deviceHash.matches("[0-9a-f]{64}")) {
            throw new ServiceException("设备证据必须为服务端生成的 SHA-256 摘要");
        }
        if (observedAt == null || observedAt.isAfter(LocalDateTime.now())) {
            throw new ServiceException("设备证据时间无效");
        }
        observedAt = observedAt.withNano(observedAt.getNano() / 1_000_000 * 1_000_000);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=?", Long.class, userId) != 1) {
            throw new ServiceException("用户不存在");
        }
        int inserted = jdbc.update("INSERT IGNORE INTO cps_risk_device_event(event_key,user_id,device_hash,observed_at,operator_id,created_at) "
            + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP(3))", eventKey, userId, deviceHash, observedAt, operator);
        if (inserted == 0) {
            Map<String, Object> existing = jdbc.queryForMap(
                "SELECT user_id,device_hash,observed_at FROM cps_risk_device_event WHERE event_key=?", eventKey);
            if (((Number) existing.get("user_id")).longValue() != userId
                || !deviceHash.equals(existing.get("device_hash"))
                || !toTime(existing.get("observed_at")).equals(observedAt)) {
                throw new ServiceException("事件标识已用于不同的设备证据");
            }
        }
    }

    /** Scans persisted evidence; re-running unchanged evidence does not re-open a cleared hold. */
    @Transactional
    public int scan() {
        int count = 0;
        long deviceUsers = policy.count("cps.risk.device.newUsers");
        long deviceMinutes = policy.count("cps.risk.device.windowMinutes");
        if (deviceUsers > 0 && deviceMinutes > 0) {
            LocalDateTime from = LocalDateTime.now().minusMinutes(deviceMinutes);
            List<Map<String, Object>> groups = jdbc.queryForList(
                "SELECT e.device_hash,COUNT(DISTINCT e.user_id) AS users,MAX(e.id) AS last_id "
                + "FROM cps_risk_device_event e JOIN sys_user u ON u.user_id=e.user_id "
                + "WHERE e.observed_at>=? AND u.create_time>=? GROUP BY e.device_hash HAVING COUNT(DISTINCT e.user_id)>=?",
                from, from, deviceUsers);
            for (Map<String, Object> group : groups) {
                List<Long> users = jdbc.queryForList("SELECT DISTINCT e.user_id FROM cps_risk_device_event e "
                    + "JOIN sys_user u ON u.user_id=e.user_id WHERE e.device_hash=? AND e.observed_at>=? AND u.create_time>=?",
                    Long.class, group.get("device_hash"), from, from);
                for (Long user : users) {
                    count += signal(user, "DEVICE_NEW_ACCOUNTS", "device:" + group.get("device_hash") + ":" + group.get("last_id"));
                }
            }
        }
        long buyers = policy.count("cps.risk.invite.sameItemBuyers");
        long orderDays = policy.count("cps.risk.orders.windowDays");
        BigDecimal lowPrice = policy.decimal("cps.risk.invite.lowPrice");
        if (buyers > 0 && orderDays > 0 && lowPrice.signum() > 0) {
            List<Map<String, Object>> groups = jdbc.queryForList(
                "SELECT referrer_user_id,platform_type,item_id,COUNT(DISTINCT buyer_user_id) AS buyers,MAX(id) AS last_id "
                + "FROM sys_order_commission WHERE referrer_user_id IS NOT NULL AND buyer_user_id IS NOT NULL "
                + "AND item_id IS NOT NULL AND item_id<>'' AND order_amount<=? AND COALESCE(paid_time,create_time)>=? "
                + "GROUP BY referrer_user_id,platform_type,item_id HAVING COUNT(DISTINCT buyer_user_id)>=?",
                lowPrice, LocalDateTime.now().minusDays(orderDays), buyers);
            for (Map<String, Object> group : groups) {
                count += signal(((Number) group.get("referrer_user_id")).longValue(), "INVITED_LOW_PRICE_CLUSTER",
                    "orders:" + group.get("platform_type") + ":" + group.get("item_id") + ":" + group.get("last_id"));
            }
        }
        long refundedOrders = policy.count("cps.risk.refund.minimumOrders");
        if (refundedOrders > 0 && orderDays > 0) {
            List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT buyer_user_id,MAX(id) AS last_id,COUNT(*) AS orders FROM sys_order_commission "
                + "WHERE buyer_user_id IS NOT NULL AND COALESCE(paid_time,create_time)>=? GROUP BY buyer_user_id "
                + "HAVING COUNT(*)>=? AND SUM(CASE WHEN line_status='FULL_REFUND' THEN 1 ELSE 0 END)=COUNT(*)",
                LocalDateTime.now().minusDays(orderDays), refundedOrders);
            for (Map<String, Object> user : users) {
                long buyer = ((Number) user.get("buyer_user_id")).longValue();
                String reference = "refunds:" + buyer + ":" + user.get("last_id") + ":" + user.get("orders");
                count += signal(buyer, "ALL_ORDERS_REFUNDED", reference);
                for (Long inviter : jdbc.queryForList("SELECT DISTINCT referrer_user_id FROM sys_order_commission "
                    + "WHERE buyer_user_id=? AND referrer_user_id IS NOT NULL AND COALESCE(paid_time,create_time)>=?",
                    Long.class, buyer, LocalDateTime.now().minusDays(orderDays))) {
                    count += signal(inviter, "INVITED_ALL_ORDERS_REFUNDED", reference);
                }
            }
        }
        return count;
    }

    private int signal(long userId, String type, String reference) {
        String evidenceKey = hash(type + ":" + userId + ":" + reference);
        int inserted = jdbc.update("INSERT IGNORE INTO cps_risk_signal(evidence_key,user_id,signal_type,evidence_ref,created_at) "
            + "VALUES (?,?,?,?,CURRENT_TIMESTAMP(3))", evidenceKey, userId, type, hash(reference));
        if (inserted == 1) {
            jdbc.update("INSERT INTO cps_risk_user_state(user_id,withdrawal_blocked,rewards_paused,manual_review,updated_at) "
                + "VALUES (?,0,1,1,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE "
                + "rewards_paused=1,manual_review=1,updated_at=CURRENT_TIMESTAMP(3)", userId);
        }
        return inserted;
    }

    /** An append-only decision audit plus a projection update; never a balance adjustment. */
    @Transactional
    public void review(long userId, boolean blockWithdrawal, boolean pauseRewards,
                       boolean manualReview, String reason, long operator) {
        requireUserId(userId);
        requireUserId(operator);
        requireText(reason, 500, "审核原因");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE user_id=?", Long.class, userId) != 1) {
            throw new ServiceException("用户不存在");
        }
        jdbc.update("INSERT INTO cps_risk_user_state(user_id,withdrawal_blocked,rewards_paused,manual_review,updated_at) "
            + "VALUES (?,?,?,?,CURRENT_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE withdrawal_blocked=VALUES(withdrawal_blocked),"
            + "rewards_paused=VALUES(rewards_paused),manual_review=VALUES(manual_review),updated_at=CURRENT_TIMESTAMP(3)",
            userId, blockWithdrawal, pauseRewards, manualReview);
        jdbc.update("INSERT INTO cps_risk_review(user_id,withdrawal_blocked,rewards_paused,manual_review,reason,operator_id,created_at) "
            + "VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(3))", userId, blockWithdrawal, pauseRewards, manualReview, reason, operator);
    }

    public List<Map<String, Object>> signals(Long userId) {
        if (userId == null) return jdbc.queryForList("SELECT * FROM cps_risk_signal ORDER BY id DESC LIMIT 200");
        requireUserId(userId);
        return jdbc.queryForList("SELECT * FROM cps_risk_signal WHERE user_id=? ORDER BY id DESC LIMIT 200", userId);
    }

    static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0;
    }

    static void requireText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) throw new ServiceException(name + "无效");
    }

    static void requireUserId(long value) {
        if (value <= 0) throw new ServiceException("用户标识无效");
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static LocalDateTime toTime(Object value) {
        return value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
    }
}
