package com.ruoyi.system.cps.attribution;

import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.union.adapter.Platform;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The database serializes allocation for one item, including the empty-pool case. */
@Service
public class PidPoolService {
    private final JdbcTemplate jdbc;
    private final AttributionSettings settings;
    private final TransactionTemplate transactions;
    private record Slot(String pid, PidType type, boolean enabled) {}

    public PidPoolService(JdbcTemplate jdbc, AttributionSettings settings, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.settings = settings; this.transactions = new TransactionTemplate(manager);
    }

    public PidAllocation allocate(Platform platform, String itemId, Long userId) {
        Objects.requireNonNull(platform); AttributionData.text(itemId, 128, "商品标识");
        if (userId != null) AttributionData.requireUser(jdbc, userId);
        return transactions.execute(status -> {
            lockPool(platform, false);
            List<Slot> slots = slots(platform);
            String fallback = defaultOf(slots);
            if (userId == null) return new PidAllocation(fallback, false, null);
            // An upsert takes an exclusive duplicate-key lock directly; INSERT IGNORE
            // followed by an upgrade can deadlock two callers on an existing mutex.
            jdbc.update("INSERT INTO cps_pid_item_mutex(platform,item_id) VALUES (?,?) ON DUPLICATE KEY UPDATE item_id=VALUES(item_id)", platform.name(), itemId);
            jdbc.queryForObject("SELECT item_id FROM cps_pid_item_mutex WHERE platform=? AND item_id=? FOR UPDATE", String.class, platform.name(), itemId);
            Instant now = AttributionData.now(jdbc);
            List<Map<String,Object>> active = jdbc.query("SELECT pid,user_id,expire_at FROM cps_pid_lease WHERE platform=? AND item_id=? AND start_at<=? AND expire_at>? ORDER BY id FOR UPDATE",
                (r,n)->Map.of("pid",r.getString("pid"),"user_id",r.getLong("user_id"),"expire_at",r.getTimestamp("expire_at")),
                platform.name(), itemId, AttributionData.timestamp(now), AttributionData.timestamp(now));
            // Reuse a user's still-valid lease before considering another slot.
            for (Slot slot : slots) if (slot.enabled && slot.type == PidType.TRACKING) {
                for (Map<String,Object> row : active) if (slot.pid.equals(row.get("pid")) && userId.longValue() == ((Number)row.get("user_id")).longValue())
                    return new PidAllocation(slot.pid, true, AttributionData.instant(row.get("expire_at")));
            }
            for (Slot slot : slots) if (slot.enabled && slot.type == PidType.TRACKING && active.stream().noneMatch(r -> slot.pid.equals(r.get("pid")))) {
                Instant expiry = now.plus(settings.leaseDuration());
                jdbc.update("INSERT INTO cps_pid_lease(platform,pid,item_id,user_id,start_at,expire_at) VALUES (?,?,?,?,?,?)",
                    platform.name(), slot.pid, itemId, userId, AttributionData.timestamp(now), AttributionData.timestamp(expiry));
                return new PidAllocation(slot.pid, true, expiry);
            }
            return new PidAllocation(fallback, false, null);
        });
    }

    /** Reserve a conversion without recycling an already published or concurrently used lease. */
    public PidConversionReservation reserveForConversion(Platform platform, String itemId, Long userId) {
        return transactions.execute(status -> {
            lockPool(platform, false);
            jdbc.update("INSERT INTO cps_pid_item_mutex(platform,item_id) VALUES (?,?) ON DUPLICATE KEY UPDATE item_id=VALUES(item_id)",platform.name(),itemId);
            jdbc.queryForObject("SELECT item_id FROM cps_pid_item_mutex WHERE platform=? AND item_id=? FOR UPDATE",String.class,platform.name(),itemId);
            Instant now=AttributionData.now(jdbc);
            List<Long> existing=userId==null ? List.of() : jdbc.queryForList("SELECT id FROM cps_pid_lease WHERE platform=? AND item_id=? AND user_id=? AND start_at<=? AND expire_at>? FOR UPDATE",Long.class,platform.name(),itemId,userId,AttributionData.timestamp(now),AttributionData.timestamp(now));
            PidAllocation allocation=allocate(platform,itemId,userId);
            if (!allocation.tracking()) return new PidConversionReservation(allocation,platform,itemId,null,null);
            Long leaseId=jdbc.queryForObject("SELECT id FROM cps_pid_lease WHERE platform=? AND item_id=? AND user_id=? AND BINARY pid=BINARY ? AND expire_at>? ORDER BY id DESC LIMIT 1 FOR UPDATE",Long.class,platform.name(),itemId,userId,allocation.pid(),AttributionData.timestamp(now));
            // A lease without reservation metadata predates this path and may already have produced links.
            jdbc.update("INSERT IGNORE INTO cps_pid_conversion_state(lease_id,published) VALUES (?,?)",leaseId,existing.contains(leaseId));
            String attempt=UUID.randomUUID().toString().replace("-","");
            jdbc.update("INSERT INTO cps_pid_conversion_attempt(attempt_id,lease_id,created_at) VALUES (?,?,?)",attempt,leaseId,AttributionData.timestamp(now));
            return new PidConversionReservation(allocation,platform,itemId,leaseId,attempt);
        });
    }

    public void finishConversion(PidConversionReservation reservation, boolean succeeded) {
        if (reservation == null || reservation.leaseId() == null) return;
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT item_id FROM cps_pid_item_mutex WHERE platform=? AND item_id=? FOR UPDATE",String.class,reservation.platform().name(),reservation.itemId());
            List<Boolean> states=jdbc.queryForList("SELECT published FROM cps_pid_conversion_state WHERE lease_id=? FOR UPDATE",Boolean.class,reservation.leaseId());
            if (states.isEmpty()) return;
            int removed=jdbc.update("DELETE FROM cps_pid_conversion_attempt WHERE attempt_id=? AND lease_id=?",reservation.attemptId(),reservation.leaseId());
            if (removed==0) return;
            boolean published=states.get(0) || succeeded;
            if (succeeded && !states.get(0)) jdbc.update("UPDATE cps_pid_conversion_state SET published=TRUE WHERE lease_id=?",reservation.leaseId());
            long remaining=jdbc.queryForObject("SELECT COUNT(*) FROM cps_pid_conversion_attempt WHERE lease_id=?",Long.class,reservation.leaseId());
            if (remaining==0) {
                if (!published) jdbc.update("DELETE FROM cps_pid_lease WHERE id=?",reservation.leaseId());
                jdbc.update("DELETE FROM cps_pid_conversion_state WHERE lease_id=?",reservation.leaseId());
            }
        });
    }

    /** Abandoned in-flight markers are safe to remove only after their lease has expired. */
    public int cleanupExpiredConversionReservations(int limit) {
        if (limit<=0 || limit>10000) throw new IllegalArgumentException("invalid cleanup batch");
        Instant now=AttributionData.now(jdbc);
        return transactions.execute(status -> {
            List<Long> ids=jdbc.queryForList("SELECT s.lease_id FROM cps_pid_conversion_state s LEFT JOIN cps_pid_lease l ON l.id=s.lease_id WHERE l.id IS NULL OR l.expire_at<=? ORDER BY s.lease_id LIMIT ? FOR UPDATE",Long.class,AttributionData.timestamp(now),limit);
            for (Long id:ids) {
                jdbc.update("DELETE FROM cps_pid_conversion_attempt WHERE lease_id=?",id);
                jdbc.update("DELETE FROM cps_pid_conversion_state WHERE lease_id=?",id);
            }
            return ids.size();
        });
    }

    public String defaultPid(Platform platform) {
        Objects.requireNonNull(platform);
        return transactions.execute(status -> { lockPool(platform, false); return defaultOf(slots(platform)); });
    }

    public PidType typeOf(Platform platform, String pid) {
        if (pid == null || pid.isBlank()) return null;
        return slots(platform).stream().filter(s -> s.pid.equals(pid)).map(Slot::type).findFirst().orElse(null);
    }

    public void register(Platform platform, String pid, PidType type, boolean enabled, Long operatorId, String reason) {
        Objects.requireNonNull(platform); Objects.requireNonNull(type);
        AttributionData.text(pid,128,"推广位"); AttributionData.text(reason,500,"操作原因");
        if (operatorId == null || operatorId <= 0) throw new ServiceException("缺少后台操作人");
        transactions.executeWithoutResult(status -> {
            lockPool(platform, true);
            List<Slot> old = slots(platform);
            Slot existing = old.stream().filter(s -> s.pid.equals(pid)).findFirst().orElse(null);
            if (existing != null && existing.type == PidType.DEFAULT && type == PidType.TRACKING)
                throw new ServiceException("默认推广位不能放入追踪池");
            if (type == PidType.TRACKING && (jdbc.queryForObject("SELECT COUNT(*) FROM cps_pid_audit WHERE platform=? AND BINARY pid=BINARY ? AND (new_type='DEFAULT' OR previous_type='DEFAULT')",Long.class,platform.name(),pid)>0 ||
                jdbc.queryForObject("SELECT COUNT(*) FROM sys_union_platform WHERE UPPER(platform_type)=? AND BINARY adzone_id=BINARY ? AND UPPER(pid_type)='DEFAULT'",Long.class,platform.name(),pid)>0))
                throw new ServiceException("默认推广位不能通过中间类型转换进入追踪池");
            if (type == PidType.DEFAULT && enabled && old.stream().anyMatch(s -> !s.pid.equals(pid) && s.type == PidType.DEFAULT && s.enabled))
                throw new ServiceException("每个平台只能启用一个默认推广位");
            if (existing != null && existing.type == PidType.TRACKING && type != PidType.TRACKING && type != PidType.BLOCKED &&
                jdbc.queryForObject("SELECT COUNT(*) FROM cps_pid_lease WHERE platform=? AND pid=? AND expire_at>?", Long.class, platform.name(), pid,AttributionData.timestamp(AttributionData.now(jdbc))) > 0)
                throw new ServiceException("存在有效租约，不能更改推广位类型");
            Instant now = AttributionData.now(jdbc);
            jdbc.update("INSERT INTO cps_pid_registry(platform,pid,pid_type,enabled,updated_by,updated_at) VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE pid_type=VALUES(pid_type),enabled=VALUES(enabled),updated_by=VALUES(updated_by),updated_at=VALUES(updated_at)",
                platform.name(),pid,type.name(),enabled,operatorId,AttributionData.timestamp(now));
            jdbc.update("INSERT INTO cps_pid_audit(platform,pid,previous_type,new_type,enabled,operator_id,reason,created_at) VALUES (?,?,?,?,?,?,?,?)",
                platform.name(),pid,existing==null?null:existing.type.name(),type.name(),enabled,operatorId,reason,AttributionData.timestamp(now));
        });
    }

    public List<PidOccupancy> occupancy(Platform platform, String itemId) {
        Objects.requireNonNull(platform);
        List<Slot> tracking = slots(platform).stream().filter(s -> s.enabled && s.type == PidType.TRACKING).toList();
        Timestamp now=AttributionData.timestamp(AttributionData.now(jdbc));
        List<String> items = itemId == null || itemId.isBlank()
            ? jdbc.queryForList("SELECT DISTINCT item_id FROM cps_pid_lease WHERE platform=? AND expire_at>? ORDER BY item_id LIMIT 500",String.class,platform.name(),now)
            : List.of(AttributionData.text(itemId,128,"商品标识"));
        List<PidOccupancy> result = new ArrayList<>();
        for (String item : items) {
            List<String> leased = jdbc.queryForList("SELECT DISTINCT pid FROM cps_pid_lease WHERE platform=? AND item_id=? AND start_at<=? AND expire_at>?",String.class,platform.name(),item,now,now);
            long count = tracking.stream().filter(s -> leased.contains(s.pid)).count();
            BigDecimal ratio = tracking.isEmpty() ? BigDecimal.ZERO : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(tracking.size()),4,RoundingMode.HALF_UP);
            result.add(new PidOccupancy(platform,item,count,tracking.size(),ratio));
        }
        return result;
    }

    private void lockPool(Platform platform, boolean exclusive) {
        jdbc.queryForObject("SELECT platform FROM cps_pid_pool_mutex WHERE platform=? " + (exclusive ? "FOR UPDATE" : "FOR SHARE"),String.class,platform.name());
    }
    private String defaultOf(List<Slot> slots) {
        List<Slot> defaults=slots.stream().filter(s -> s.enabled && s.type == PidType.DEFAULT).toList();
        if (defaults.size()!=1) throw new ServiceException("请配置且只启用一个默认推广位");
        return defaults.get(0).pid;
    }
    private List<Slot> slots(Platform platform) {
        Map<String,Slot> merged = new LinkedHashMap<>();
        // Existing explicitly typed slots remain usable. No null/unknown type becomes TRACKING.
        jdbc.query("SELECT adzone_id,pid_type,is_quote FROM sys_union_platform WHERE UPPER(platform_type)=? AND pid_type IS NOT NULL AND adzone_id IS NOT NULL ORDER BY id FOR SHARE", rs -> {
            String pid=rs.getString(1), raw=rs.getString(2);
            if (pid.isBlank()) return;
            PidType type;
            try { type=PidType.valueOf(raw.trim().toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException invalid) { return; }
            Slot slot=new Slot(pid,type,!"2".equals(rs.getString(3)));
            Slot previous=merged.putIfAbsent(pid,slot);
            if (previous!=null && previous.type!=type) throw new ServiceException("同一推广位存在冲突类型配置");
        },platform.name());
        jdbc.query("SELECT pid,pid_type,enabled FROM cps_pid_registry WHERE platform=? ORDER BY pid FOR SHARE", rs -> {
            Slot slot=new Slot(rs.getString(1),PidType.valueOf(rs.getString(2)),rs.getBoolean(3));
            Slot legacy=merged.get(slot.pid);
            // A legacy block/default cannot be weakened by an override from another screen.
            if (legacy != null && legacy.type == PidType.BLOCKED) slot=new Slot(slot.pid,PidType.BLOCKED,false);
            if (legacy != null && legacy.type == PidType.DEFAULT && slot.type == PidType.TRACKING)
                throw new ServiceException("默认推广位与追踪池配置冲突");
            merged.put(slot.pid,slot);
        },platform.name());
        return List.copyOf(merged.values());
    }
}
