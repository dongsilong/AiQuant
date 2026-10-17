package org.dromara.quant.execution;

import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.AccountBalanceCache;
import org.dromara.quant.market.AccountPositionCache;
import org.dromara.quant.risk.LiveTradingGate;
import org.dromara.quant.risk.OpenCapacityService;
import org.dromara.quant.util.QuantJsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 启动/配置变化时异步准备；行情执行只读取准备令牌，不修改已有持仓的交易设置。
 */
@Service
@Slf4j
public class TradingPreparationService {
    private final ExchangeGateway gateway;
    private final LiveTradingGate liveGate;
    private final AccountPositionCache positions;
    private final AccountBalanceCache balances;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile LiveOrderDispatcher liveOrderDispatcher;
    public TradingPreparationService(ExchangeGateway gateway, LiveTradingGate liveGate, AccountPositionCache positions, AccountBalanceCache balances) {
        this.gateway = gateway;
        this.liveGate = liveGate;
        this.positions = positions;
        this.balances = balances;
    }

    private static String normalizeMode(String mode) {
        if (mode == null || "ISOLATED".equalsIgnoreCase(mode)) {
            return "ISOLATED";
        }
        if ("CROSSED".equalsIgnoreCase(mode) || "CROSS".equalsIgnoreCase(mode)) {
            return "CROSSED";
        }
        throw new IllegalArgumentException("不支持的保证金模式");
    }

    private static String key(Long id, String symbol) {
        return id + ":" + symbol.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 注入 LIVE 订单派发器，用于串行化账户交易配置请求。
     */
    @Autowired
    public void setLiveOrderDispatcher(LiveOrderDispatcher dispatcher) {
        this.liveOrderDispatcher = java.util.Objects.requireNonNull(dispatcher);
    }

    /**
     * 异步检查并准备交易对杠杆、保证金模式及交易规则。
     */
    public CompletableFuture<Void> request(Long id, ExchangeGateway.Credential credential, String symbol, int leverage, String marginMode, ExecutionSafetyContext safety) {
        if ("PAPER".equalsIgnoreCase(credential.runMode())) {
            return CompletableFuture.completedFuture(null);
        }
        String mode = normalizeMode(marginMode);
        String key = key(id, symbol);
        State state = states.compute(key,
            (ignored, current) -> current != null && current.credential.equals(credential) && current.leverage == leverage && current.marginMode.equals(mode) && Objects.equals(current.safety, safety) ? current : new State(id, credential, symbol, leverage, mode, safety));
        return refresh(key, state);
    }

    /**
     * 读取未过期且参数匹配的准备结果；否则拒绝下单。
     */
    public Prepared requireReady(Long id, String symbol, int leverage, String marginMode) {
        String normalizedMode = normalizeMode(marginMode);
        State state = states.get(key(id, symbol));
        if (state == null) {
            throw new OpenCapacityService.CapacityBlockedException("交易设置尚未初始化，等待后台首次校准: symbol=" + symbol);
        }
        if (state != null) {
            synchronized (state) {
                // 实盘拦截优先暴露
                if (state.liveFailures != null) {
                    throw new LiveTradingGate.LiveTradingBlockedException(state.liveFailures);
                }
                if (state.leverage == leverage && state.marginMode.equals(normalizedMode) && state.prepared != null && System.nanoTime() < state.prepared.expiresAtNanos()) {
                    return state.prepared;
                }
            }
        }
        String ex = "交易设置未准备好或已变化，等待后台校准及新的行情: symbol=" + symbol + ", leverage=" + leverage + ", marginMode=" + normalizedMode;
        throw new OpenCapacityService.CapacityBlockedException(ex);
    }

    private CompletableFuture<Void> refresh(String key, State state) {
        synchronized (state) {
            if (state.loading != null) {
                return state.loading;
            }
            if (System.nanoTime() < state.retryAt || state.prepared != null && System.nanoTime() - state.checkedAt < TimeUnit.SECONDS.toNanos(30)) {
                return CompletableFuture.completedFuture(null);
            }
            long revision = state.revision;
            var future = new CompletableFuture<Void>();
            state.loading = future;
            Thread.ofVirtual().name("quant-trading-preparation").start(() -> {
                long started = System.nanoTime();
                try {
                    // 同账户同交易对的旧/新配置任务串行，网络等待不占用行情线程或数据库事务。
                    synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
                        if (states.get(key) != state) {
                            return;
                        }
                        balances.requireSnapshot(state.id);
                        var active = positions.requirePositions(state.id);
                        var mode = gateway.queryPositionMode(state.credential);
                        var rule = gateway.queryTradingRule(state.credential, state.symbol);
                        if (mode == null || rule == null || rule.minNotional() == null || rule.minNotional().signum() <= 0 || state.leverage <= 0) {
                            throw new IllegalStateException("交易准备缺少有效模式、规则或杠杆");
                        }
                        if (!state.configured) {
                            if (active.stream().anyMatch(position -> state.symbol.equalsIgnoreCase(position.symbol()) && position.quantity().signum() != 0)) {
                                throw new IllegalStateException("已有持仓，禁止后台修改保证金模式或杠杆");
                            }
                            if ("LIVE".equalsIgnoreCase(state.credential.runMode())) {
                                var safety = state.safety;
                                if (safety == null) {
                                    throw new LiveTradingGate.LiveTradingBlockedException();
                                }
                                liveGate.check(new LiveTradingGate.Checkpoint(safety.systemLiveEnabled(), safety.accountLiveEnabled(), safety.futuresTradePermission(),
                                    safety.withdrawalDisabled(), safety.connectivityVerified(), safety.accountAuthorized(), safety.riskConfigured(), true,
                                    safety.approvedAndNotTripped()));
                            }
                            // REST 读取期间发生外部配置变化/断线时不得继续设置。
                            synchronized (state) {
                                if (states.get(key) != state || revision != state.revision) {
                                    return;
                                }
                            }
                            balances.requireSnapshot(state.id);
                            if (positions.requirePositions(state.id).stream().anyMatch(position -> state.symbol.equalsIgnoreCase(position.symbol()) && position.quantity().signum() != 0)) {
                                throw new IllegalStateException("准备期间出现持仓，禁止修改交易设置");
                            }
                            LiveOrderDispatcher.Ticket configTicket = null;
                            LiveOrderDispatcher dispatcher = null;
                            try {
                                if ("LIVE".equalsIgnoreCase(state.credential.runMode())) {
                                    dispatcher = liveOrderDispatcher;
                                    if (dispatcher == null) {
                                        throw new IllegalStateException("LIVE 账户配置缺少跨节点调度器，拒绝直接修改 Binance");
                                    }
                                    // Hold ONE durable account+symbol slot across BOTH Binance writes.
                                    // An order or another node's preparation cannot enter between them.
                                    String dispatchKey = "CFG:" + java.util.UUID.randomUUID();
                                    configTicket = dispatcher.beginConfiguration(state.id, state.symbol, dispatchKey, state.marginMode, state.leverage);
                                    // Another node may have acquired/released the slot while we waited.
                                    // Revalidate local revision and fresh position facts AFTER claim.
                                    synchronized (state) {
                                        if (states.get(key) != state || revision != state.revision) {
                                            throw new IllegalStateException("配置等待调度锁期间发生版本变化，拒绝旧配置写入");
                                        }
                                    }
                                    balances.requireSnapshot(state.id);
                                    if (positions.requirePositions(state.id).stream().anyMatch(position -> state.symbol.equalsIgnoreCase(position.symbol()) && position.quantity().signum() != 0)) {
                                        throw new IllegalStateException("取得配置锁后出现持仓，禁止修改保证金或杠杆");
                                    }
                                }
                                gateway.setMarginType(state.credential, state.symbol, state.marginMode);
                                synchronized (state) {
                                    if (states.get(key) != state || revision != state.revision) {
                                        throw new IllegalStateException("保证金设置后配置版本变化，拒绝继续修改杠杆");
                                    }
                                }
                                balances.requireSnapshot(state.id);
                                if (positions.requirePositions(state.id).stream().anyMatch(position -> state.symbol.equalsIgnoreCase(position.symbol()) && position.quantity().signum() != 0)) {
                                    throw new IllegalStateException("准备期间出现持仓，禁止修改杠杆");
                                }
                                gateway.setLeverage(state.credential, state.symbol, state.leverage);
                                if (configTicket != null) {
                                    // A setter HTTP success is not independent proof of
                                    // actual Binance leverage/margin, especially after
                                    // retries or ambiguous exchanges. Read fresh facts
                                    // under the same persistent cross-node dispatch slot.
                                    var verified = gateway.querySymbolTradingSettings(state.credential, state.symbol);
                                    if (verified == null || !state.symbol.equalsIgnoreCase(verified.symbol()) || !state.marginMode.equals(verified.marginMode()) || state.leverage != verified.leverage()) {
                                        throw new IllegalStateException("Binance 实际保证金或杠杆与目标配置不一致，保留调度阻断");
                                    }
                                    dispatcher.configurationApplied(configTicket);
                                    configTicket = null;
                                }
                            } catch (RuntimeException ex) {
                                if (configTicket != null) {
                                    try {
                                        // No definitive exchange order ID exists for settings.
                                        // Even an early failure is quarantined until operator proof.
                                        dispatcher.unknown(configTicket);
                                    } catch (RuntimeException quarantineError) {
                                        ex.addSuppressed(quarantineError);
                                    }
                                }
                                throw ex;
                            }
                        }
                        // A previously configured account can drift out of band.
                        // Verify exchange facts before renewing the entry token.
                        if (state.configured && "LIVE".equalsIgnoreCase(state.credential.runMode())) {
                            var verified = gateway.querySymbolTradingSettings(state.credential, state.symbol);
                            if (verified == null || !state.symbol.equalsIgnoreCase(verified.symbol()) || !state.marginMode.equals(verified.marginMode()) || state.leverage != verified.leverage()) {
                                throw new IllegalStateException("Binance 账户配置漂移或无法核实，拒绝续期交易准备令牌");
                            }
                        }
                        synchronized (state) {
                            if (states.get(key) == state && revision == state.revision) {
                                balances.requireSnapshot(state.id);
                                state.configured = true;
                                state.liveFailures = null;
                                state.checkedAt = System.nanoTime();
                                state.prepared = new Prepared(mode, rule, state.checkedAt + TimeUnit.SECONDS.toNanos(60));
                            }
                        }
                    }
                    log.debug("交易后台准备 credentialId={} symbol={} elapsedMillis={}", state.id, state.symbol, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                } catch (Exception ex) {
                    synchronized (state) {
                        state.prepared = null;
                        if (states.get(key) == state && revision == state.revision) {
                            state.liveFailures = ex instanceof LiveTradingGate.LiveTradingBlockedException blocked ? blocked.getFailures() : null;
                        }
                        state.retryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    }
                    log.debug("交易后台准备未就绪 credentialId={} symbol={} elapsedMillis={} reason={}", state.id, state.symbol,
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), ex.getClass().getSimpleName());
                } finally {
                    synchronized (state) {
                        if (state.loading == future) {
                            state.loading = null;
                        }
                    }
                    future.complete(null);
                }
            });
            return future;
        }
    }

    /**
     * 使账户准备状态失效，要求后续决策重新核验。
     */
    public void invalidate(Long id) {
        states.values().stream().filter(state -> state.id.equals(id)).forEach(state -> {
            synchronized (state) {
                state.prepared = null;
                state.liveFailures = null;
                state.configured = false;
                state.revision++;
            }
        });
    }

    /**
     * 策略停止后不继续预热该交易对；在释放运行租约前等待已开始的配置请求结束。
     */
    public void release(Long id, String symbol) {
        State state = states.remove(key(id, symbol));
        if (state != null) {
            CompletableFuture<Void> loading;
            synchronized (state) {
                state.revision++;
                state.prepared = null;
                loading = state.loading;
            }
            if (loading != null) {
                loading.join();
            }
        }
    }

    /**
     * 清除账户的本地准备状态。
     */
    public void remove(Long id) {
        invalidate(id);
        states.entrySet().removeIf(entry -> entry.getValue().id.equals(id));
    }

    /**
     * 在账户设置变更事件后使相关准备缓存失效。
     */
    public void onEvent(Long id, ExchangeGateway.UserDataEvent event) {
        if (event == null) {
            return;
        }
        if ("DISCONNECTED".equals(event.eventType()) || "listenKeyExpired".equals(event.eventType())) {
            invalidate(id);
            return;
        }
        if (!"ACCOUNT_CONFIG_UPDATE".equals(event.eventType()) && !"ACCOUNT_UPDATE".equals(event.eventType())) {
            return;
        }
        try {
            var json = QuantJsonMapper.readMap(event.rawJson());
            if ("ACCOUNT_CONFIG_UPDATE".equals(event.eventType())) {
                if (json.get("ac") instanceof Map<?, ?> config && config.get("l") instanceof Number leverage) {
                    String symbol = Objects.toString(config.get("s"), "");
                    if (symbol.isBlank()) {
                        invalidate(id);
                        return;
                    }
                    State state = states.get(key(id, symbol));
                    // 相同设置的回执不会废弃正在完成的准备，外部不同设置立即失效。
                    if (state != null && state.leverage != leverage.intValue()) {
                        invalidate(id);
                    }
                } else {
                    invalidate(id);
                }
            } else if (json.get("a") instanceof Map<?, ?> account && "MARGIN_TYPE_CHANGE".equals(account.get("m"))) {
                if (!(account.get("P") instanceof java.util.List<?> changes) || changes.isEmpty()) {
                    invalidate(id);
                    return;
                }
                for (Object change : changes) {
                    if (!(change instanceof Map<?, ?> row)) {
                        invalidate(id);
                        return;
                    }
                    State state = states.get(key(id, Objects.toString(row.get("s"), "")));
                    String rawMode = Objects.toString(row.get("mt"), "");
                    if (!"isolated".equalsIgnoreCase(rawMode) && !"cross".equalsIgnoreCase(rawMode)) {
                        invalidate(id);
                        return;
                    }
                    String actual = "isolated".equalsIgnoreCase(rawMode) ? "ISOLATED" : "CROSSED";
                    if (state != null && !state.marginMode.equals(actual)) {
                        invalidate(id);
                        return;
                    }
                }
            }
        } catch (Exception ex) {
            invalidate(id);
        }
    }

    /**
     * 周期性刷新过期或失效的账户准备状态。
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void reconcile() {
        states.forEach(this::refresh);
    }

    /**
     * 已校验的交易模式和交易对规则，以及本地有效期限。
     */
    public record Prepared(ExchangeGateway.PositionMode positionMode, ExchangeGateway.SymbolRule rule, long expiresAtNanos) {
    }

    private static final class State {
        private final Long id;
        private final ExchangeGateway.Credential credential;
        private final String symbol;
        private final int leverage;
        private final String marginMode;
        private final ExecutionSafetyContext safety;
        private volatile boolean configured;
        private Prepared prepared;
        private java.util.List<String> liveFailures;
        private long revision;
        private long checkedAt;
        private long retryAt;
        private CompletableFuture<Void> loading;

        private State(Long id, ExchangeGateway.Credential credential, String symbol, int leverage, String marginMode, ExecutionSafetyContext safety) {
            this.id = id;
            this.credential = credential;
            this.symbol = symbol;
            this.leverage = leverage;
            this.marginMode = marginMode;
            this.safety = safety;
        }
    }
}
