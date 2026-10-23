package org.dromara.quant.market;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.risk.OpenCapacityService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 账户共享可用余额；行情线程只读，网络校准最多每账户一个任务。
 */
@RequiredArgsConstructor
@Component
@Slf4j
public class AccountBalanceCache {
    private static final long MAX_AGE = TimeUnit.SECONDS.toNanos(30);
    private static final long AUDIT_INTERVAL = TimeUnit.SECONDS.toNanos(10);
    private final ExchangeGateway gateway;
    private final OpenCapacityService capacity;
    private final Map<Long, Account> accounts = new ConcurrentHashMap<>();

    private static void invalidate(Account account) {
        account.snapshot = null;
        account.revision++;
    }

    /**
     * 开始跟踪账户余额，并绑定当前用户流健康状态。
     */
    public void begin(Long id, ExchangeGateway.Credential credential, BooleanSupplier healthy) {
        accounts.put(id, new Account(credential, healthy));
    }

    /**
     * 标记用户流断开，使余额快照不能继续作为新决策依据。
     */
    public void disconnect(Long id) {
        Account account = accounts.get(id);
        if (account != null) {
            synchronized (account) {
                account.connected = false;
                invalidate(account);
            }
        }
    }

    /**
     * 清除账户在本进程中的余额与刷新状态。
     */
    public void remove(Long id) {
        disconnect(id);
        accounts.remove(id);
    }

    /**
     * 返回仍可信的余额快照；缺失、过期或流不健康时拒绝使用。
     */
    public AccountBalanceSnapshot requireSnapshot(Long id) {
        Account account = accounts.get(id);
        if (account != null) {
            synchronized (account) {
                if (account.connected && account.healthy.getAsBoolean() && account.snapshot != null && account.snapshot.fresh()) {
                    return account.snapshot;
                }
            }
            refresh(id);
        }
        throw new OpenCapacityService.CapacityBlockedException("账户可用余额未就绪、过期或断线，等待后台校准及新的行情");
    }

    /**
     * 按用户数据流事件增量更新余额，并保留快照的新鲜度语义。
     */
    public void onEvent(Long id, ExchangeGateway.UserDataEvent event) {
        if (event == null) {
            return;
        }
        if ("DISCONNECTED".equals(event.eventType()) || "listenKeyExpired".equals(event.eventType())) {
            disconnect(id);
        } else if ("CONNECTED".equals(event.eventType())) {
            Account account = accounts.get(id);
            if (account != null) {
                synchronized (account) {
                    account.connected = true;
                    invalidate(account);
                    account.retryAt = 0L;
                }
            }
            refresh(id);
        } else if ("ACCOUNT_UPDATE".equals(event.eventType()) || "ORDER_TRADE_UPDATE".equals(event.eventType())) {
            Account account = accounts.get(id);
            if (account != null) {
                synchronized (account) {
                    invalidate(account);
                }
            }
            // 不在接收线程发 REST；一秒调度合并同账户一批更新。
        }
    }

    /**
     * 查询交易所最新余额并与并发到达的流事件协调合并。
     */
    public CompletableFuture<Void> refresh(Long id) {
        Account account = accounts.get(id);
        if (account == null) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (account) {
            if (!account.connected || !account.healthy.getAsBoolean() || System.nanoTime() < account.retryAt) {
                return CompletableFuture.completedFuture(null);
            }
            if (account.loading != null) {
                return account.loading;
            }
            long revision = account.revision;
            var loading = new CompletableFuture<Void>();
            account.loading = loading;
            Thread.ofVirtual().name("quant-balance-snapshot").start(() -> {
                long started = System.nanoTime();
                try {
                    // 先取已提交的成交集合，再请求余额，不依赖应用节点与数据库的时钟一致性。
                    var covered = capacity.balanceCoverage(id);
                    var balance = gateway.queryAccountFresh(account.credential);
                    if (balance == null || balance.availableBalance() == null || balance.availableBalance().signum() < 0) {
                        throw new IllegalStateException("可用余额响应无效");
                    }
                    synchronized (account) {
                        if (accounts.get(id) == account && account.connected && account.healthy.getAsBoolean() && revision == account.revision && System.nanoTime() - started < MAX_AGE) {
                            account.snapshot = new AccountBalanceSnapshot(id, balance.availableBalance(), covered, started + MAX_AGE);
                            account.snapshotAt = started;
                        }
                        account.failures = 0;
                        account.retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000 + Math.floorMod(id, 1000));
                    }
                    log.debug("账户余额后台校准 credentialId={} elapsedMillis={} snapshotAccepted={}", id, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                        revision == account.revision);
                } catch (Exception ex) {
                    synchronized (account) {
                        if (!(ex instanceof org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException) || account.snapshot == null || !account.snapshot.fresh()) {
                            invalidate(account);
                        }
                        account.failures = Math.min(3, account.failures + 1);
                        long delay = Math.min(10_000L, 1000L << account.failures);
                        account.retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay + java.util.concurrent.ThreadLocalRandom.current().nextLong(1000));
                    }
                    log.warn("账户余额后台校准失败 credentialId={} elapsedMillis={} errorType={} snapshotFresh={} reason={}", id,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ex.getClass().getSimpleName(), account.snapshot != null && account.snapshot.fresh(),
                        ex instanceof org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException ? ex.getMessage() : "请检查校准响应或网络状态");
                } finally {
                    synchronized (account) {
                        if (account.loading == loading) {
                            account.loading = null;
                        }
                    }
                    loading.complete(null);
                }
            });
            return loading;
        }
    }

    /**
     * 定期校验账户余额快照并安排必要的 REST 刷新。
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void reconcile() {
        accounts.forEach((id, account) -> {
            synchronized (account) {
                if (account.snapshot == null || System.nanoTime() - account.snapshotAt >= AUDIT_INTERVAL) {
                    refresh(id);
                }
            }
        });
    }

    private static final class Account {
        private final ExchangeGateway.Credential credential;
        private final BooleanSupplier healthy;
        private boolean connected = true;
        private volatile long revision;
        private int failures;
        private long retryAt;
        private long snapshotAt;
        private AccountBalanceSnapshot snapshot;
        private CompletableFuture<Void> loading;

        private Account(ExchangeGateway.Credential credential, BooleanSupplier healthy) {
            this.credential = credential;
            this.healthy = healthy;
        }
    }
}
