package com.ruoyi.system.cps.attribution;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import jakarta.annotation.PreDestroy;
import com.ruoyi.union.adapter.*;
import com.ruoyi.union.dtk.api.client.DtkApiRequest;
import com.ruoyi.union.dtk.api.client.DtkClient;
import com.ruoyi.union.dtk.config.DtkConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** New bounded coupon path; avoids the old mutable client singleton and its sensitive error logging. */
@Component
public class CouponDtkGateway implements DtkCouponGateway {
    private final DtkConfig credentials;
    private final CouponSettings settings;
    private final PidPoolService pids;
    private final Map<Platform, BoundedUnionExecutor> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final BiFunction<String, String, DtkClient> clients;
    @Autowired
    public CouponDtkGateway(DtkConfig credentials, CouponSettings settings, PidPoolService pids) {
        this(credentials, settings, pids, null);
    }
    /** Package-local transport seam; production always constructs the safe fixed-host client. */
    CouponDtkGateway(DtkConfig credentials, CouponSettings settings, PidPoolService pids,
            BiFunction<String, String, DtkClient> clients) {
        this.credentials = credentials; this.settings = settings; this.pids = pids;
        this.clients = clients;
    }
    @Override public <T> T execute(Platform platform, DtkApiRequest<T> request) {
        BoundedUnionExecutor queue = queues.compute(platform, (p, existing) -> {
            if (closed.get()) throw new UnionCouponException("UPSTREAM_BUSY", "联盟服务正在关闭，请稍后重试");
            // Reading inside per-platform compute prevents an older concurrent reader
            // from restoring stale limits after a newer call has already applied them.
            int parallelism = settings.integer("cps.coupon.union-parallelism", 4, 1, 64);
            int capacity = settings.integer("cps.coupon.union-queue-capacity", 32, 1, 10000);
            Duration timeout = Duration.ofMillis(settings.integer("cps.coupon.union-timeout-ms", 15000, 100, 120000));
            if (existing == null) return new BoundedUnionExecutor(parallelism, capacity, timeout);
            existing.reconfigure(parallelism, capacity, timeout);
            return existing;
        });
        return queue.execute(() -> executeSigned(request));
    }
    private <T> T executeSigned(DtkApiRequest<T> request) {
        DtkClient client = clients == null
            ? new SafeDtkCouponClient(credentials.getAppKey(), credentials.getAppSecret(),
                settings.integer("cps.coupon.connect-timeout-ms", 3000, 100, 120000),
                settings.integer("cps.coupon.read-timeout-ms", 10000, 100, 120000))
            : clients.apply(credentials.getAppKey(), credentials.getAppSecret());
        return client.execute(request);
    }
    @Override public String defaultPid(Platform platform) { return pids.defaultPid(platform); }
    @Override public String jdUnionId() {
        String id = settings.text("cps.coupon.jd-union-id", "");
        if (!id.matches("[1-9][0-9]{0,19}")) throw new UnionCouponException("UPSTREAM_NOT_CONFIGURED", "京东联盟账号尚未配置");
        return id;
    }
    @PreDestroy public void close() {
        if (!closed.compareAndSet(false, true)) return;
        // computeIfPresent synchronizes with an in-progress initial creation for that
        // platform, so a pool cannot be published after the shutdown walk has passed it.
        for (Platform platform : Platform.values()) queues.computeIfPresent(platform, (p, queue) -> {
            queue.close();
            return queue;
        });
    }
}
