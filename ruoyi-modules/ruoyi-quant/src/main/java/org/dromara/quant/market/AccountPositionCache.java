package org.dromara.quant.market;

import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.risk.OpenCapacityService;
import org.dromara.quant.util.QuantJsonMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 开仓容量检查使用的账户持仓事实，不用于估值或替代订单恢复账本。
 * 完整快照初始化后由私有流增量维护；未知状态禁止新开仓。
 */
@Component
@Slf4j
public class AccountPositionCache {
    private static final long AUDIT_INTERVAL = TimeUnit.SECONDS.toNanos(30);
    private static final long MAX_SNAPSHOT_AGE = TimeUnit.SECONDS.toNanos(90);
    private final ExchangeGateway gateway;
    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();

    public AccountPositionCache(ExchangeGateway gateway) {
        this.gateway = gateway;
    }

    /** 必须在订阅之前创建新代次，以缓存连接建立期间的增量。 */
    public void begin(Long credentialId, ExchangeGateway.Credential credential, BooleanSupplier healthy) {
        accounts.put(credentialId, new Account(credential, healthy));
    }

    public void disconnect(Long credentialId) {
        Account account = accounts.get(credentialId);
        if (account != null) {
            synchronized (account) {
                account.connected = false;
                invalidate(account);
            }
        }
    }

    public void remove(Long credentialId) {
        disconnect(credentialId);
        accounts.remove(credentialId);
    }

    /** 只读取本地可信快照，不等待网络，也不把未知账户当成空仓。 */
    public List<ExchangeGateway.PositionSnapshot> requirePositions(Long credentialId) {
        Account account = accounts.get(credentialId);
        if (account != null) {
            synchronized (account) {
                if (account.connected && account.healthy.getAsBoolean() && account.ready
                    && System.nanoTime() - account.snapshotAt <= MAX_SNAPSHOT_AGE) {
                    return List.copyOf(account.positions.values());
                }
                if (account.ready) { invalidate(account); }
            }
        }
        throw new OpenCapacityService.CapacityBlockedException("账户持仓流未就绪、断线或对账过期，暂停新开仓，等待完整持仓校准");
    }

    /** 接收线程先更新/失效缓存，再交给可能阻塞的策略消费者。 */
    public void onEvent(Long credentialId, ExchangeGateway.UserDataEvent event) {
        Account account = accounts.get(credentialId);
        if (account == null || event == null) { return; }
        if ("DISCONNECTED".equals(event.eventType()) || "listenKeyExpired".equals(event.eventType())) {
            disconnect(credentialId);
            return;
        }
        if ("CONNECTED".equals(event.eventType())) {
            refresh(credentialId);
            return;
        }
        if (!"ACCOUNT_UPDATE".equals(event.eventType())) { return; }
        synchronized (account) {
            if (!account.connected) { return; }
            try {
                Map<String, Object> json = QuantJsonMapper.readMap(event.rawJson());
                if (!(json.get("T") instanceof Number time) || time.longValue() <= 0
                    || !(json.get("a") instanceof Map<?, ?> update) || !(update.get("P") instanceof List<?> positions)) {
                    throw new IllegalArgumentException("缺少持仓增量字段");
                }
                // 先完整解析，避免半个事件成功、半个事件失败。
                List<ExchangeGateway.PositionSnapshot> changes = new ArrayList<>();
                for (Object item : positions) {
                    if (!(item instanceof Map<?, ?> row)) { throw new IllegalArgumentException("持仓增量格式错误"); }
                    var position = new ExchangeGateway.PositionSnapshot(Objects.toString(row.get("s"), ""),
                        Objects.toString(row.get("ps"), ""), new BigDecimal(Objects.toString(row.get("pa"), "")),
                        null, null, null, null, null, null, null, null);
                    validate(position);
                    changes.add(position);
                }
                for (var position : changes) {
                    String key = key(position);
                    long previous = account.eventTimes.getOrDefault(key, Long.MIN_VALUE);
                    // BOTH 零仓对两侧都生效，记录墓碑，防止旧事件重新制造持仓。
                    if ("BOTH".equals(position.side()) && position.quantity().signum() == 0) {
                        String longKey = position.symbol().toUpperCase(Locale.ROOT) + ":LONG";
                        String shortKey = position.symbol().toUpperCase(Locale.ROOT) + ":SHORT";
                        previous = Math.max(account.eventTimes.getOrDefault(longKey, Long.MIN_VALUE),
                            account.eventTimes.getOrDefault(shortKey, Long.MIN_VALUE));
                        if (time.longValue() < previous) { continue; }
                        account.eventTimes.put(longKey, time.longValue());
                        account.eventTimes.put(shortKey, time.longValue());
                        if (account.ready) {
                            account.positions.remove(longKey);
                            account.positions.remove(shortKey);
                        }
                    } else {
                        if (time.longValue() < previous) { continue; }
                        account.eventTimes.put(key, time.longValue());
                        if (account.ready) {
                            if (position.quantity().signum() == 0) { account.positions.remove(key); }
                            else { account.positions.put(key, position); }
                        }
                    }
                    if (!account.ready && position.quantity().signum() != 0) {
                        // 快照与增量缺少统一序列号：初始化保守合并，不能用零增量删掉快照中的仓位。
                        account.buffered.put(key, position);
                    }
                    account.revision++;
                }
            } catch (Exception ex) {
                invalidate(account);
                log.warn("账户持仓增量无法校验，暂停新开仓 credentialId={}", credentialId);
            }
        }
    }

    /** 每账户最多一个后台查询；开仓线程不调用此方法。 */
    public CompletableFuture<Void> refresh(Long credentialId) {
        Account account = accounts.get(credentialId);
        if (account == null) { return CompletableFuture.completedFuture(null); }
        synchronized (account) {
            if (!account.connected || !account.healthy.getAsBoolean()) {
                invalidate(account);
                return CompletableFuture.completedFuture(null);
            }
            if (account.loading != null) { return account.loading; }
            long generation = account.generation;
            long revision = account.revision;
            boolean bootstrap = !account.ready;
            var loading = new CompletableFuture<Void>();
            account.loading = loading;
            Thread.ofVirtual().name("quant-position-snapshot").start(() -> {
                try {
                    var snapshots = gateway.queryPositions(account.credential, null);
                    if (snapshots == null) { throw new IllegalStateException("持仓快照为空响应"); }
                    Map<String, ExchangeGateway.PositionSnapshot> full = new HashMap<>();
                    for (var position : snapshots) {
                        validate(position);
                        if (position.quantity().signum() != 0) {
                            if (position.updateTime() == null || position.updateTime().toEpochMilli() <= 0) {
                                throw new IllegalStateException("持仓快照缺少交易所更新时间");
                            }
                            full.put(key(position), position);
                        }
                    }
                    synchronized (account) {
                        if (accounts.get(credentialId) != account || generation != account.generation
                            || !account.connected || !account.healthy.getAsBoolean()) { return; }
                        if (bootstrap) {
                            account.buffered.forEach((key, buffered) -> {
                                var snapshot = full.get(key);
                                if (snapshot == null || account.eventTimes.getOrDefault(key, Long.MIN_VALUE)
                                    >= snapshot.updateTime().toEpochMilli()) { full.put(key, buffered); }
                            });
                            // 快照仓位的更新时间也是事件墓碑，旧零仓事件不能删除较新的真实持仓。
                            seedTimes(account, full.values());
                            account.positions.clear();
                            account.positions.putAll(full);
                            account.buffered.clear();
                            account.ready = true;
                            account.snapshotAt = System.nanoTime();
                        } else if (revision == account.revision) {
                            if (!sameQuantities(account.positions, full)) {
                                invalidate(account);
                                log.warn("账户持仓对账存在差异，暂停新开仓 credentialId={}", credentialId);
                            } else {
                                seedTimes(account, full.values());
                                account.snapshotAt = System.nanoTime();
                            }
                        }
                        // 对账期间发生增量时不覆盖，也不延长完整对账有效期。
                    }
                } catch (Exception ex) {
                    synchronized (account) { if (generation == account.generation) { invalidate(account); } }
                    log.warn("账户持仓快照获取失败，暂停新开仓 credentialId={}", credentialId);
                } finally {
                    synchronized (account) { if (account.loading == loading) { account.loading = null; } }
                    loading.complete(null);
                }
            });
            return loading;
        }
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void reconcile() {
        accounts.forEach((id, account) -> {
            synchronized (account) {
                if (!account.ready || System.nanoTime() - account.snapshotAt >= AUDIT_INTERVAL) { refresh(id); }
            }
        });
    }

    private static void seedTimes(Account account, Collection<ExchangeGateway.PositionSnapshot> positions) {
        for (var position : positions) {
            if (position.updateTime() != null) {
                account.eventTimes.merge(key(position), position.updateTime().toEpochMilli(), Math::max);
            }
        }
    }

    private static void invalidate(Account account) {
        account.ready = false;
        account.generation++;
        account.buffered.clear();
    }

    private static String key(ExchangeGateway.PositionSnapshot position) {
        String side = position.side();
        if ("BOTH".equals(side)) { side = position.quantity().signum() < 0 ? "SHORT" : "LONG"; }
        return position.symbol().toUpperCase(Locale.ROOT) + ":" + side;
    }

    private static void validate(ExchangeGateway.PositionSnapshot position) {
        if (position == null || position.symbol() == null || position.symbol().isBlank() || position.quantity() == null
            || !Set.of("LONG", "SHORT", "BOTH").contains(position.side())) {
            throw new IllegalArgumentException("持仓事实字段无效");
        }
    }

    private static boolean sameQuantities(Map<String, ExchangeGateway.PositionSnapshot> first,
                                          Map<String, ExchangeGateway.PositionSnapshot> second) {
        return first.keySet().equals(second.keySet()) && first.entrySet().stream()
            .allMatch(entry -> entry.getValue().quantity().compareTo(second.get(entry.getKey()).quantity()) == 0);
    }

    private static final class Account {
        private final ExchangeGateway.Credential credential;
        private final BooleanSupplier healthy;
        private final Map<String, ExchangeGateway.PositionSnapshot> positions = new HashMap<>();
        private final Map<String, ExchangeGateway.PositionSnapshot> buffered = new HashMap<>();
        private final Map<String, Long> eventTimes = new HashMap<>();
        private boolean connected = true;
        private boolean ready;
        private long generation;
        private long revision;
        private long snapshotAt;
        private CompletableFuture<Void> loading;

        private Account(ExchangeGateway.Credential credential, BooleanSupplier healthy) {
            this.credential = credential;
            this.healthy = healthy;
        }
    }
}
