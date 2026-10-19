package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.quant.account.domain.QuantAccountSnapshot;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantAccountSnapshotMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.decision.TradeDecisionLogService;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.StrictBinanceTradeCollector;
import org.dromara.quant.execution.StrategyFillJournalService;
import org.dromara.quant.finance.domain.QuantFinancialEvent;
import org.dromara.quant.finance.domain.vo.QuantFinancialEventVo;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.dromara.quant.reconcile.domain.QuantReconcileRecord;
import org.dromara.quant.reconcile.mapper.QuantReconcileRecordMapper;
import org.dromara.quant.reconcile.mapper.StrategyFillSyncCursorMapper;
import org.dromara.quant.runtime.service.StrategyPositionStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 交易所事实与本地交易账本的最终一致性入口。
 * <p>订单使用历史订单接口而非仅当前挂单接口；成交按最近窗口持续同步，避免成交后从 openOrders 中消失导致本地状态停在 NEW。
 */
@Service
@Slf4j
public class TradingReconciliationService {
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * 账户快照 Mapper。
     */
    private final QuantAccountSnapshotMapper accountSnapshotMapper;
    /**
     * 持仓 Mapper。
     */
    private final QuantPositionMapper positionMapper;
    /**
     * 订单 Mapper。
     */
    private final QuantOrderMapper orderMapper;
    /**
     * 成交 Mapper。
     */
    private final QuantTradeMapper tradeMapper;
    /**
     * 保护单 Mapper。
     */
    private final QuantProtectionOrderMapper protectionOrderMapper;
    /**
     * 财务事件 Mapper。
     */
    private final QuantFinancialEventMapper financialEventMapper;
    /**
     * 对账记录 Mapper。
     */
    private final QuantReconcileRecordMapper reconcileMapper;
    private final ReconciliationFailureRecorder failureRecorder;
    private final StrategyPositionStateService strategyPositionStateService;
    private final TransactionTemplate transaction;
    private final ConcurrentHashMap<Long, ReentrantLock> positionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CompletableFuture<List<ExchangeGateway.PositionSnapshot>>> positionRefreshes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, CachedPositionSnapshot> runtimePositionSnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong> runtimeSnapshotGenerations = new ConcurrentHashMap<>();
    private static final Duration RUNTIME_POSITION_CACHE_AGE = Duration.ofSeconds(20);
    @Autowired
    private TradeDecisionLogService decisionLogs;
    private final StrictBinanceTradeCollector tradeCollector;
    private final StrategyFillJournalService fillJournal;
    private final StrategyFillSyncCursorMapper fillSyncCursors;
    private final StrategyFillSyncCursorService fillSyncCursorService;
    @Autowired(required = false)
    private LivePositionFreshness positionFreshness;
    @Autowired
    private org.dromara.quant.reconcile.mapper.LivePositionSnapshotFenceMapper snapshotFence;
    /**
     * Share quant_credential FOR UPDATE with OpenCapacityService.reserve().
     * This serializes position facts and new capacity reservations across nodes.
     */
    @Autowired
    private org.dromara.quant.risk.mapper.OpenCapacityMapper openCapacityMapper;
    /**
     * 创建 TradingReconciliationService 实例并初始化其依赖。
     */
    public TradingReconciliationService(ExchangeGateway gateway, QuantAccountSnapshotMapper accountSnapshotMapper, QuantPositionMapper positionMapper,
                                        QuantOrderMapper orderMapper, QuantTradeMapper tradeMapper, QuantProtectionOrderMapper protectionOrderMapper,
                                        QuantFinancialEventMapper financialEventMapper, QuantReconcileRecordMapper reconcileMapper, ReconciliationFailureRecorder failureRecorder
        , StrategyPositionStateService strategyPositionStateService, PlatformTransactionManager transactionManager, StrictBinanceTradeCollector tradeCollector,
                                        StrategyFillJournalService fillJournal, StrategyFillSyncCursorMapper fillSyncCursors, StrategyFillSyncCursorService fillSyncCursorService) {
        this.gateway = gateway;
        this.accountSnapshotMapper = accountSnapshotMapper;
        this.positionMapper = positionMapper;
        this.orderMapper = orderMapper;
        this.tradeMapper = tradeMapper;
        this.protectionOrderMapper = protectionOrderMapper;
        this.financialEventMapper = financialEventMapper;
        this.reconcileMapper = reconcileMapper;
        this.failureRecorder = failureRecorder;
        this.strategyPositionStateService = strategyPositionStateService;
        this.tradeCollector = tradeCollector;
        this.fillJournal = fillJournal;
        this.fillSyncCursors = fillSyncCursors;
        this.fillSyncCursorService = fillSyncCursorService;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(30);
    }
    /**
     * Commit an account's verified REST snapshot under a database row lock.
     * Earlier-started requests cannot overwrite later-started snapshots.
     */

    /**
     * 统一保护单持仓方向，兼容单向/双向持仓模式。
     */
    private static String normalizeProtectionSide(String value) {
        if (value == null || value.isBlank() || "BOTH".equalsIgnoreCase(value)) {
            return null;
        }
        if ("LONG".equalsIgnoreCase(value)) {
            return "LONG";
        }
        if ("SHORT".equalsIgnoreCase(value)) {
            return "SHORT";
        }
        return value.toUpperCase();
    }

    /**
     * 判断交易所保护单是否仍处于活动状态。
     */
    private static boolean isOpenProtection(String status) {
        return "NEW".equalsIgnoreCase(status) || "WORKING".equalsIgnoreCase(status) || "ACCEPTED".equalsIgnoreCase(status);
    }

    /**
     * 把非空交易对加入本轮待同步集合。
     */
    private static void addSymbol(Set<String> symbols, String symbol) {
        if (symbol != null && !symbol.isBlank()) {
            symbols.add(symbol);
        }
    }

    /**
     * 值为空时返回默认值。
     */
    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ReentrantLock positionLock(Long credentialId) {
        return positionLocks.computeIfAbsent(credentialId, ignored -> new ReentrantLock());
    }

    /**
     * 仅在刷新代次未变化且快照完整时提交账户持仓事实。
     */
    private void commitPositionSnapshot(QuantCredential credential, ExchangeGateway.AccountSnapshot account, List<ExchangeGateway.PositionSnapshot> positions,
                                        LocalDateTime fetchStartedAt, long eventGeneration, long runtimeGeneration, String trigger) {
        var accountLock = positionLock(credential.getId());
        accountLock.lock();
        try {
            if (positionFreshness != null && positionFreshness.generation(credential.getId()) != eventGeneration) {
                throw new SnapshotSupersededException();
            }
            if (runtimeSnapshotGeneration(credential.getId()) != runtimeGeneration) {
                throw new SnapshotSupersededException("Account position snapshot superseded by a private-stream event");
            }
            transaction.executeWithoutResult(status -> {
                if (openCapacityMapper != null && openCapacityMapper.lockCredential(credential.getId()).isEmpty()) {
                    throw new IllegalStateException("Cannot lock credential for position snapshot reconciliation");
                }
                if (snapshotFence != null) {
                    snapshotFence.ensure(credential.getId());
                    LocalDateTime previous = snapshotFence.lockAndRead(credential.getId());
                    if (previous != null && previous.isAfter(fetchStartedAt)) {
                        throw new IllegalStateException("Binance position snapshot superseded by another node");
                    }
                }
                if (positionFreshness != null && positionFreshness.generation(credential.getId()) != eventGeneration) {
                    throw new SnapshotSupersededException();
                }
                syncAccount(credential, account);
                syncPositions(credential, positions);
                if (snapshotFence != null && snapshotFence.advance(credential.getId(), fetchStartedAt) != 1) {
                    throw new IllegalStateException("Position snapshot fence advance failed");
                }
            });
            markPositionVerified(credential.getId(), eventGeneration);
            runtimePositionSnapshots.put(credential.getId(), new CachedPositionSnapshot(eventGeneration, runtimeGeneration, Instant.now(), positions));
        } finally {
            accountLock.unlock();
        }
        long openPositionCount = positions.stream().filter(p -> p.quantity() != null && p.quantity().signum() != 0).count();
        log.info("账户级持仓快照已提交 credentialId={} runMode={} generation={} trigger={} snapshotCount={} openPositionCount={} elapsedMs={}", credential.getId(), credential.getRunMode(),
            eventGeneration, trigger, positions.size(), openPositionCount, java.time.Duration.between(fetchStartedAt, LocalDateTime.now()).toMillis());
    }

    private long positionGeneration(Long credentialId) {
        return positionFreshness == null ? 0L : positionFreshness.generation(credentialId);
    }

    private long runtimeSnapshotGeneration(Long credentialId) {
        return runtimeSnapshotGenerations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
    }

    private void markPositionVerified(Long credentialId, long generation) {
        if (positionFreshness != null) {
            positionFreshness.verifiedIfUnchanged(credentialId, generation);
        }
    }

    /**
     * Startup/triggered position-only refresh. No historical orders or fills
     * are required to persist an authenticated full account position snapshot.
     * Never treat an unavailable exchange response as an empty account.
     */
    public void refreshPositionsOnly(QuantCredential credential) {
        refreshAccountPositionSnapshot(credential, "scheduled-or-event");
    }

    /**
     * Return a recent, complete account snapshot for runtime recovery, fetching and committing one
     * shared snapshot when the cache is missing, stale, or invalidated by a private-stream event.
     */
    public List<ExchangeGateway.PositionSnapshot> runtimePositionSnapshot(QuantCredential credential) {
        if (credential == null || credential.getId() == null) {
            throw new IllegalArgumentException("Credential is required for a runtime position snapshot");
        }
        CachedPositionSnapshot cached = runtimePositionSnapshots.get(credential.getId());
        long generation = positionGeneration(credential.getId());
        long runtimeGeneration = runtimeSnapshotGeneration(credential.getId());
        if (cached != null && cached.positionGeneration() == generation && cached.runtimeGeneration() == runtimeGeneration && Duration.between(cached.fetchedAt(), Instant.now()).compareTo(RUNTIME_POSITION_CACHE_AGE) <= 0
            && (!"LIVE".equalsIgnoreCase(credential.getRunMode()) || positionFreshness == null || positionFreshness.fresh(credential.getId()))) {
            log.debug("运行时复用账户级持仓快照 credentialId={} generation={} ageMs={}", credential.getId(), generation,
                Duration.between(cached.fetchedAt(), Instant.now()).toMillis());
            return cached.positions();
        }
        return refreshAccountPositionSnapshot(credential, "runtime-reconcile");
    }

    /** Invalidate the in-memory account snapshot after a private-stream event. */
    public void invalidateRuntimePositionSnapshot(Long credentialId) {
        if (credentialId != null) {
            var accountLock = positionLock(credentialId);
            accountLock.lock();
            try {
                runtimeSnapshotGenerations.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
                runtimePositionSnapshots.remove(credentialId);
            } finally {
                accountLock.unlock();
            }
        }
    }

    private List<ExchangeGateway.PositionSnapshot> refreshAccountPositionSnapshot(QuantCredential credential, String trigger) {
        if (credential == null || credential.getId() == null) {
            throw new IllegalArgumentException("Credential is required for an account position snapshot");
        }
        long credentialId = credential.getId();
        CompletableFuture<List<ExchangeGateway.PositionSnapshot>> created = new CompletableFuture<>();
        CompletableFuture<List<ExchangeGateway.PositionSnapshot>> existing = positionRefreshes.putIfAbsent(credentialId, created);
        if (existing != null) {
            long waitStartedAt = System.nanoTime();
            List<ExchangeGateway.PositionSnapshot> snapshots = joinPositionRefresh(existing);
            log.debug("运行时等待共享账户持仓快照 credentialId={} trigger={} waitMs={}", credentialId, trigger,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStartedAt));
            return snapshots;
        }
        try {
            long generation = positionGeneration(credentialId);
            long runtimeGeneration = runtimeSnapshotGeneration(credentialId);
            LocalDateTime fetchStartedAt = LocalDateTime.now();
            ExchangeGateway.Credential exchangeCredential = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(),
                credential.getRunMode());
            // Network I/O stays outside the account write lock and database transaction.
            var account = java.util.Objects.requireNonNull(gateway.queryAccount(exchangeCredential), "Binance account snapshot missing");
            var snapshots = List.copyOf(java.util.Objects.requireNonNull(gateway.queryPositions(exchangeCredential, null), "Binance full position snapshot missing"));
            commitPositionSnapshot(credential, account, snapshots, fetchStartedAt, generation, runtimeGeneration, trigger);
            created.complete(snapshots);
            return snapshots;
        } catch (RuntimeException ex) {
            failureRecorder.record(credentialId, ex.getMessage());
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            positionRefreshes.remove(credentialId, created);
        }
    }

    private List<ExchangeGateway.PositionSnapshot> joinPositionRefresh(CompletableFuture<List<ExchangeGateway.PositionSnapshot>> refresh) {
        try {
            return refresh.join();
        } catch (CompletionException ex) {
            if (ex.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw ex;
        }
    }

    /**
     * Persist one symbol snapshot through the same account lock and cross-node fence as a full snapshot.
     * A symbol snapshot only changes that symbol; it must never be treated as a complete account snapshot.
     */
    public void syncSymbolPositionSnapshot(QuantCredential credential, String symbol, List<ExchangeGateway.PositionSnapshot> snapshots, LocalDateTime fetchStartedAt,
                                           long eventGeneration) {
        if (credential == null || credential.getId() == null) {
            throw new IllegalArgumentException("Credential is required for a symbol position snapshot");
        }
        syncSymbolPositionSnapshot(credential, symbol, snapshots, fetchStartedAt, eventGeneration, runtimeSnapshotGeneration(credential.getId()));
    }

    private void syncSymbolPositionSnapshot(QuantCredential credential, String symbol, List<ExchangeGateway.PositionSnapshot> snapshots, LocalDateTime fetchStartedAt,
                                            long eventGeneration, long runtimeGeneration) {
        if (credential == null || credential.getId() == null || symbol == null || symbol.isBlank() || snapshots == null || fetchStartedAt == null) {
            throw new IllegalArgumentException("Complete symbol position snapshot metadata is required");
        }
        long generation = eventGeneration;
        var accountLock = positionLock(credential.getId());
        accountLock.lock();
        try {
            if (positionFreshness != null && positionFreshness.generation(credential.getId()) != generation) {
                throw new SnapshotSupersededException();
            }
            if (runtimeSnapshotGeneration(credential.getId()) != runtimeGeneration) {
                throw new SnapshotSupersededException("Binance symbol position snapshot superseded by a private-stream event");
            }
            transaction.executeWithoutResult(status -> {
                if (openCapacityMapper != null && openCapacityMapper.lockCredential(credential.getId()).isEmpty()) {
                    throw new IllegalStateException("Cannot lock credential for symbol position snapshot");
                }
                if (snapshotFence != null) {
                    snapshotFence.ensure(credential.getId());
                    LocalDateTime previous = snapshotFence.lockAndRead(credential.getId());
                    if (previous != null && previous.isAfter(fetchStartedAt)) {
                        throw new SnapshotSupersededException("Binance symbol position snapshot superseded by another node");
                    }
                }
                if (positionFreshness != null && positionFreshness.generation(credential.getId()) != generation) {
                    throw new SnapshotSupersededException();
                }
                syncSymbolPosition(credential, symbol, snapshots);
                if (snapshotFence != null && snapshotFence.advance(credential.getId(), fetchStartedAt) != 1) {
                    throw new IllegalStateException("Symbol position snapshot fence advance failed");
                }
            });
            runtimeSnapshotGenerations.computeIfAbsent(credential.getId(), ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
            runtimePositionSnapshots.remove(credential.getId());
        } finally {
            accountLock.unlock();
        }
    }

    /**
     * Fetch and persist a symbol snapshot while serializing same-process requests for this account.
     */
    public List<ExchangeGateway.PositionSnapshot> refreshSymbolPosition(QuantCredential credential, String symbol, ExchangeGateway.Credential exchangeCredential) {
        if (credential == null || credential.getId() == null || symbol == null || symbol.isBlank() || exchangeCredential == null) {
            throw new IllegalArgumentException("Credential, symbol and exchange credentials are required");
        }
        LocalDateTime fetchStartedAt = LocalDateTime.now();
        long generation = positionGeneration(credential.getId());
        long runtimeGeneration = runtimeSnapshotGeneration(credential.getId());
        List<ExchangeGateway.PositionSnapshot> snapshots = List.copyOf(java.util.Objects.requireNonNull(gateway.queryPositions(exchangeCredential, symbol), "Binance symbol " +
            "position snapshot missing"));
        syncSymbolPositionSnapshot(credential, symbol, snapshots, fetchStartedAt, generation, runtimeGeneration);
        return snapshots;
    }

    /**
     * 对账指定交易对持仓，保留无法唯一归属的原始策略份额。
     */
    private void syncSymbolPosition(QuantCredential credential, String symbol, List<ExchangeGateway.PositionSnapshot> snapshots) {
        if (snapshots.stream().anyMatch(snapshot -> !symbol.equalsIgnoreCase(snapshot.symbol()))) {
            throw new IllegalStateException("Binance returned a position outside the requested symbol: " + symbol);
        }
        List<ExchangeGateway.PositionSnapshot> symbolFacts = snapshots.stream().filter(snapshot -> symbol.equalsIgnoreCase(snapshot.symbol())).toList();
        long nonZeroCount = symbolFacts.stream().filter(snapshot -> snapshot.quantity() != null && snapshot.quantity().signum() != 0).count();
        if (nonZeroCount > 1) {
            throw new IllegalStateException("同一凭证同一交易对存在多个非零持仓方向，拒绝自动接管: " + symbol);
        }
        syncPositions(credential, symbolFacts, symbol);
    }

    /**
     * Query Binance outside of a transaction; missing facts must not partially overwrite
     * account positions, orders or protections.
     */
    public void reconcileCredential(QuantCredential credential, int historyHours) {
        try {
            long positionGeneration = positionGeneration(credential.getId());
            long runtimeGeneration = runtimeSnapshotGeneration(credential.getId());
            LocalDateTime fetchStartedAt = LocalDateTime.now();
            ExchangeGateway.Credential exchangeCredential = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(),
                credential.getRunMode());
            // Collect a complete account position snapshot before any DB writes.
            // Commit it independently BEFORE querying order/fill/income history:
            // a failing history endpoint must not keep already-closed Binance
            // positions counted as open indefinitely.
            var account = java.util.Objects.requireNonNull(gateway.queryAccount(exchangeCredential), "Binance account snapshot missing");
            var positions = List.copyOf(java.util.Objects.requireNonNull(gateway.queryPositions(exchangeCredential, null), "Binance full position snapshot missing"));
            commitPositionSnapshot(credential, account, positions, fetchStartedAt, positionGeneration, runtimeGeneration, "full-reconcile");
            ExchangeFacts facts = collectExchangeFacts(credential, historyHours, exchangeCredential, account, positions);
            transaction.executeWithoutResult(status -> applyExchangeFacts(credential, facts));
        } catch (RuntimeException ex) {
            failureRecorder.record(credential.getId(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * 对单个交易凭证执行完整事实对账。
     */

    /**
     * 收集并校验账户、持仓和成交事实，缺项时不写入部分同步结果。
     */
    private ExchangeFacts collectExchangeFacts(QuantCredential credential, int historyHours, ExchangeGateway.Credential c, ExchangeGateway.AccountSnapshot account,
                                               List<ExchangeGateway.PositionSnapshot> positions) {
        LocalDateTime now = LocalDateTime.now();
        ExchangeGateway.TimeRange range = new ExchangeGateway.TimeRange(now.minusHours(Math.max(1, historyHours)).toInstant(ZoneOffset.UTC), now.toInstant(ZoneOffset.UTC));
        var openOrders = List.copyOf(gateway.queryOrders(c, null));
        var history = List.copyOf(gateway.queryOrderHistory(c, null, range, 1000L));
        Set<String> symbols = new HashSet<>();
        positions.forEach(p -> addSymbol(symbols, p.symbol()));
        history.forEach(o -> addSymbol(symbols, o.symbol()));
        openOrders.forEach(o -> addSymbol(symbols, o.symbol()));
        symbols.addAll(fillSyncCursors.selectSymbols(credential.getId()));
        Map<String, List<ExchangeGateway.TradeSnapshot>> trades = new HashMap<>();
        Map<String, Instant> fillCursorEnds = new HashMap<>();
        Set<String> fillHistoryBacklogSymbols = new HashSet<>();
        Map<String, List<ExchangeGateway.ProtectionOrderSnapshot>> protections = new HashMap<>();
        for (String symbol : symbols) {
            LocalDateTime storedCursor = fillSyncCursors.selectLastCompleteTo(credential.getId(), symbol);
            Instant from = storedCursor == null ? range.from() : storedCursor.toInstant(ZoneOffset.UTC);
            Instant to = range.to();
            if (from.isAfter(to)) {
                throw new IllegalStateException("策略成交同步游标位于未来，拒绝跳过成交历史: credentialId=" + credential.getId() + ", symbol=" + symbol);
            }
            if (Duration.between(from, to).compareTo(Duration.ofDays(180)) > 0) {
                to = from.plus(Duration.ofDays(180));
            }
            if (to.isBefore(range.to())) {
                fillHistoryBacklogSymbols.add(symbol);
            }
            if (from.isBefore(to)) {
                trades.put(symbol, tradeCollector.collect(c, symbol, from, to));
                fillCursorEnds.put(symbol, to);
            } else {
                trades.put(symbol, List.of());
            }
            protections.put(symbol, List.copyOf(gateway.queryProtectionOrders(c, symbol)));
        }
        var income = List.copyOf(gateway.queryIncomeHistory(c, null, null, range, 1000L));
        return new ExchangeFacts(account, positions, openOrders, history, trades, fillCursorEnds, fillHistoryBacklogSymbols, protections, income);
    }

    /**
     * Only database writes are permitted inside this short transaction.
     */
    private void applyExchangeFacts(QuantCredential credential, ExchangeFacts facts) {
        syncOrders(credential, facts.history());
        syncOrders(credential, facts.openOrders());
        if ("LIVE".equalsIgnoreCase(credential.getRunMode())) {
            fillJournal.retryPending(credential.getId());
        }
        for (var entry : facts.trades().entrySet()) {
            syncTrades(credential, entry.getValue());
        }
        for (var entry : facts.fillCursorEnds().entrySet()) {
            LocalDateTime cursorEnd = LocalDateTime.ofInstant(entry.getValue(), ZoneOffset.UTC);
            fillSyncCursorService.advance(credential.getId(), entry.getKey(), cursorEnd);
        }
        long unattributedFills = "LIVE".equalsIgnoreCase(credential.getRunMode()) ? fillJournal.countUnresolved(credential.getId()) : 0L;
        int unmatchedProtections = 0;
        for (var entry : facts.protections().entrySet()) {
            unmatchedProtections += syncProtectionOrders(credential, entry.getKey(), facts.positions(), entry.getValue());
        }
        syncFinancialEvents(credential, facts.income());
        int fillHistoryBacklogSymbols = facts.fillHistoryBacklogSymbols().size();
        if (unmatchedProtections > 0 || unattributedFills > 0 || fillHistoryBacklogSymbols > 0) {
            // Unmatched protections, unattributed fills, or a catch-up cursor
            // behind the current interval may not be reported as complete.
            reconcileMapper.insert(record(credential.getId(), "DEGRADED",
                "{\"unmatchedLiveProtections\":" + unmatchedProtections + ",\"unattributedFills\":" + unattributedFills + ",\"fillHistoryBacklogSymbols\":" + fillHistoryBacklogSymbols + "}"));
        } else {
            reconcileMapper.insert(record(credential.getId(), "SUCCESS", "{}"));
        }
    }

    /**
     * 同步账户资产快照。
     */
    private void syncAccount(QuantCredential credential, ExchangeGateway.AccountSnapshot snapshot) {
        LocalDateTime observedAt = LocalDateTime.now().withNano(0);
        QuantAccountSnapshot entity = accountSnapshotMapper.selectOne(new LambdaQueryWrapper<QuantAccountSnapshot>().eq(QuantAccountSnapshot::getCredentialId,
            credential.getId()).eq(QuantAccountSnapshot::getObservedAt, observedAt));
        if (entity == null) {
            entity = new QuantAccountSnapshot();
            entity.setCredentialId(credential.getId());
            entity.setObservedAt(observedAt);
        }
        entity.setWalletBalance(nvl(snapshot.walletBalance()));
        entity.setAvailableBalance(nvl(snapshot.availableBalance()));
        entity.setMarginBalance(nvl(snapshot.marginBalance()));
        entity.setUnrealizedPnl(nvl(snapshot.unrealizedPnl()));
        entity.setRealizedPnl(BigDecimal.ZERO);
        entity.setFundingFee(BigDecimal.ZERO);
        if (entity.getId() == null) {
            accountSnapshotMapper.insert(entity);
        } else {
            accountSnapshotMapper.updateById(entity);
        }
    }

    /**
     * 同步交易所持仓事实。
     */
    private void syncPositions(QuantCredential credential, List<ExchangeGateway.PositionSnapshot> snapshots) {
        syncPositions(credential, snapshots, null);
    }

    /**
     * 按已验证的交易所快照同步持仓，不将单个交易对查询误作全账户事实。
     */
    private void syncPositions(QuantCredential credential, List<ExchangeGateway.PositionSnapshot> snapshots, String scopedSymbol) {
        Set<String> activeKeys = new HashSet<>();
        for (ExchangeGateway.PositionSnapshot s : snapshots) {
            if (s.quantity() == null || s.quantity().signum() == 0) {
                continue;
            }
            String direction = "BOTH".equalsIgnoreCase(s.side()) ? (s.quantity().signum() >= 0 ? "LONG" : "SHORT") : s.side().toUpperCase();
            String key = s.symbol().toUpperCase() + ":" + direction;
            activeKeys.add(key);
            QuantPosition entity =
                positionMapper.selectOne(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credential.getId()).eq(QuantPosition::getSymbol, s.symbol()).eq(QuantPosition::getPositionSide, direction));
            if (entity == null) {
                entity = new QuantPosition();
                entity.setCredentialId(credential.getId());
                entity.setSymbol(s.symbol());
                entity.setPositionSide(direction);
            }
            entity.setQuantity(s.quantity().abs());
            entity.setEntryPrice(nvl(s.entryPrice()));
            entity.setMarkPrice(nvl(s.markPrice()));
            entity.setUnrealizedPnl(nvl(s.unrealizedPnl()));
            entity.setLiquidationPrice(s.liquidationPrice());
            entity.setMargin(s.margin());
            entity.setLeverage(s.leverage());
            entity.setUpdatedAt(LocalDateTime.now());
            if (entity.getId() == null) {
                positionMapper.insert(entity);
            } else {
                positionMapper.updateById(entity);
            }
        }

        var localQuery = new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credential.getId()).and(query -> query.gt(QuantPosition::getQuantity,
            BigDecimal.ZERO).or().inSql(QuantPosition::getId, "SELECT position_id FROM quant_strategy_position_state WHERE status <> 'CLOSED' " + "AND (position_qty > 0 OR " +
            "pending_action IS NOT NULL OR status='PROTECTION_CLEANUP')"));
        if (scopedSymbol != null) {
            localQuery.eq(QuantPosition::getSymbol, scopedSymbol);
        }
        List<QuantPosition> locals = positionMapper.selectList(localQuery);
        for (QuantPosition local : locals) {
            String key = local.getSymbol().toUpperCase() + ":" + local.getPositionSide().toUpperCase();
            if (!activeKeys.contains(key)) {
                BigDecimal previousQuantity = local.getQuantity();
                if (previousQuantity != null && previousQuantity.signum() > 0) {
                    local.setQuantity(BigDecimal.ZERO);
                    local.setUnrealizedPnl(BigDecimal.ZERO);
                    local.setUpdatedAt(LocalDateTime.now());
                    positionMapper.updateById(local);
                }
                strategyPositionStateService.markClosedByPositionId(local.getId());
                if (previousQuantity != null && previousQuantity.signum() > 0) {
                    log.info("Binance持仓快照确认本地仓位归零 credentialId={} positionId={} symbol={} side={} previousQuantity={}", credential.getId(), local.getId(), local.getSymbol(),
                        local.getPositionSide(), previousQuantity);
                }
            }
        }
    }

    /**
     * 同步交易所订单事实。
     */
    private void syncOrders(QuantCredential credential, List<ExchangeGateway.OrderSnapshot> snapshots) {
        for (ExchangeGateway.OrderSnapshot s : snapshots) {
            String clientOrderId = s.clientOrderId() == null ? "EXCHANGE:" + s.orderId() : s.clientOrderId();
            QuantOrder entity = s.orderId() == null ? null :
                orderMapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credential.getId()).eq(QuantOrder::getSymbol, s.symbol())
                    .eq(QuantOrder::getExchangeOrderId, s.orderId()));
            if (entity == null) {
                entity = orderMapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credential.getId()).eq(QuantOrder::getClientOrderId,
                    clientOrderId).eq(QuantOrder::getSymbol, s.symbol()));
            }
            if (entity == null) {
                entity = new QuantOrder();
                entity.setCredentialId(credential.getId());
                entity.setClientOrderId(clientOrderId);
                entity.setCreatedAt(LocalDateTime.now());
                entity.setIdempotencyKey(clientOrderId);
            }
            entity.setSymbol(s.symbol());
            entity.setExchangeOrderId(s.orderId());
            entity.setSide(s.side());
            entity.setOrderType(s.type());
            entity.setStatus(s.status());
            entity.setPrice(s.price());
            entity.setOriginalQuantity(nvl(s.originalQuantity()));
            entity.setExecutedQuantity(nvl(s.executedQuantity()));
            entity.setUpdatedAt(s.updateTime() == null ? LocalDateTime.now() : LocalDateTime.ofInstant(s.updateTime(), ZoneOffset.UTC));
            if (entity.getId() == null) {
                orderMapper.insert(entity);
            } else {
                orderMapper.updateById(entity);
            }
        }
    }

    /**
     * 同步交易所成交事实。
     */
    private void syncTrades(QuantCredential credential, List<ExchangeGateway.TradeSnapshot> snapshots) {
        for (ExchangeGateway.TradeSnapshot s : snapshots) {
            if (decisionLogs != null) {
                decisionLogs.trade(credential.getId(), s);
            }
            QuantTrade entity = tradeMapper.selectOne(new LambdaQueryWrapper<QuantTrade>().eq(QuantTrade::getCredentialId, credential.getId()).eq(QuantTrade::getExchangeTradeId,
                s.tradeId()));
            if (entity == null) {
                entity = new QuantTrade();
                entity.setCredentialId(credential.getId());
                entity.setExchangeTradeId(s.tradeId());
            }
            entity.setSymbol(s.symbol());
            entity.setExchangeOrderId(s.orderId());
            QuantOrder sourceOrder = s.orderId() == null ? null :
                orderMapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credential.getId()).eq(QuantOrder::getSymbol, s.symbol())
                    .eq(QuantOrder::getExchangeOrderId, s.orderId()));
            if (sourceOrder != null) {
                entity.setStrategyId(sourceOrder.getStrategyId());
                entity.setStrategyInstanceId(sourceOrder.getStrategyInstanceId());
            }
            if ("LIVE".equalsIgnoreCase(credential.getRunMode())) {
                fillJournal.attribute(s, sourceOrder, credential.getId());
            }
            entity.setSide(s.side());
            entity.setQuantity(nvl(s.quantity()));
            entity.setPrice(nvl(s.price()));
            entity.setCommission(nvl(s.commission()));
            entity.setRealizedPnl(nvl(s.realizedPnl()));
            entity.setFundingFee(BigDecimal.ZERO);
            entity.setTradeTime(s.time() == null ? LocalDateTime.now() : LocalDateTime.ofInstant(s.time(), ZoneOffset.UTC));
            if (entity.getId() == null) {
                tradeMapper.insert(entity);
            } else {
                tradeMapper.updateById(entity);
            }
        }
    }

    /**
     * 同步交易所财务流水到独立财务事件账本。
     */
    private void syncFinancialEvents(QuantCredential credential, List<ExchangeGateway.IncomeSnapshot> snapshots) {
        for (ExchangeGateway.IncomeSnapshot s : snapshots) {
            if (s.transactionId() == null) {
                continue;
            }
            QuantFinancialEventVo vo = financialEventMapper.selectVoOne(new LambdaQueryWrapper<QuantFinancialEvent>().eq(QuantFinancialEvent::getCredentialId,
                credential.getId()).eq(QuantFinancialEvent::getExchangeTransactionId, s.transactionId()));
            QuantFinancialEvent entity = MapstructUtils.convert(vo, QuantFinancialEvent.class);
            if (entity == null) {
                entity = new QuantFinancialEvent();
                entity.setCredentialId(credential.getId());
                entity.setExchangeTransactionId(s.transactionId());
                entity.setCreatedAt(LocalDateTime.now());
            }
            entity.setSymbol(s.symbol());
            entity.setEventType(s.incomeType());
            entity.setAmount(nvl(s.amount()));
            entity.setAsset(s.asset());
            entity.setInfo(s.info());
            entity.setEventTime(s.time() == null ? LocalDateTime.now() : LocalDateTime.ofInstant(s.time(), ZoneOffset.UTC));
            if (entity.getId() == null) {
                financialEventMapper.insert(entity);
            } else {
                financialEventMapper.updateById(entity);
            }
        }
    }

    /**
     * 同步交易所保护单状态。
     */
    private int syncProtectionOrders(QuantCredential credential, String symbol, List<ExchangeGateway.PositionSnapshot> positions,
                                     List<ExchangeGateway.ProtectionOrderSnapshot> snapshots) {
        int unmatchedLiveProtections = 0;
        Set<Long> seenIds = new HashSet<>();
        Set<String> seenClients = new HashSet<>();
        for (ExchangeGateway.ProtectionOrderSnapshot s : snapshots) {
            QuantPosition position = findProtectionPosition(credential.getId(), symbol, positions, s.positionSide(), s.side());
            QuantProtectionOrder entity = protectionOrderMapper.selectOne(new LambdaQueryWrapper<QuantProtectionOrder>().eq(QuantProtectionOrder::getCredentialId,
                credential.getId()).eq(QuantProtectionOrder::getExchangeOrderId, s.algoId()));
            if (entity == null && s.clientAlgoId() != null) {
                entity =
                    protectionOrderMapper.selectOne(new LambdaQueryWrapper<QuantProtectionOrder>().eq(QuantProtectionOrder::getCredentialId, credential.getId()).eq(QuantProtectionOrder::getClientOrderId, s.clientAlgoId()));
            }
            if (entity == null) {
                entity = new QuantProtectionOrder();
                entity.setCredentialId(credential.getId());
                entity.setClientOrderId(s.clientAlgoId());
                entity.setProtectionType("EXCHANGE_CONDITIONAL");
            }
            entity.setPositionId(position == null ? null : position.getId());
            entity.setSymbol(symbol);
            entity.setPositionSide(position == null ? normalizeProtectionSide(s.positionSide()) : position.getPositionSide());
            entity.setExchangeOrderId(s.algoId());
            entity.setOrderType(s.type());
            entity.setQuantity(s.quantity());
            entity.setStatus(s.status());
            entity.setStopPrice(s.triggerPrice());
            if (entity.getId() == null) {
                protectionOrderMapper.insert(entity);
            } else {
                protectionOrderMapper.updateById(entity);
            }
            // Missing local position mapping is not proof of an absent exchange position.
            // Reconciliation must not cancel a possibly live protective order.
            if (position == null && isOpenProtection(s.status())) {
                unmatchedLiveProtections++;
            }
            if (s.algoId() != null) {
                seenIds.add(s.algoId());
            }
            if (s.clientAlgoId() != null) {
                seenClients.add(s.clientAlgoId());
            }
        }

        List<QuantProtectionOrder> locals = protectionOrderMapper.selectList(new LambdaQueryWrapper<QuantProtectionOrder>().eq(QuantProtectionOrder::getCredentialId,
            credential.getId()).eq(QuantProtectionOrder::getSymbol, symbol).in(QuantProtectionOrder::getStatus, "NEW", "WORKING", "ACCEPTED"));
        boolean positionExists = positions.stream().anyMatch(p -> symbol.equalsIgnoreCase(p.symbol()) && p.quantity() != null && p.quantity().signum() != 0);
        for (QuantProtectionOrder local : locals) {
            boolean stillOpen =
                (local.getExchangeOrderId() != null && seenIds.contains(local.getExchangeOrderId())) || (local.getClientOrderId() != null && seenClients.contains(local.getClientOrderId()));
            if (!stillOpen) {
                local.setStatus(positionExists ? "MISSING" : "CANCELED");
                protectionOrderMapper.updateById(local);
            }
        }
        return unmatchedLiveProtections;
    }

    /**
     * 根据保护单定位对应本地持仓。
     */
    private QuantPosition findProtectionPosition(Long credentialId, String symbol, List<ExchangeGateway.PositionSnapshot> snapshots, String exchangePositionSide,
                                                 String orderSide) {
        String wanted = normalizeProtectionSide(exchangePositionSide);
        if (wanted == null && orderSide != null) {
            if ("SELL".equalsIgnoreCase(orderSide)) {
                wanted = "LONG";
            }
            if ("BUY".equalsIgnoreCase(orderSide)) {
                wanted = "SHORT";
            }
        }
        final String matchedDirection = wanted;
        return positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credentialId).eq(QuantPosition::getSymbol, symbol).gt(QuantPosition::getQuantity, BigDecimal.ZERO)).stream().filter(p -> matchedDirection == null || matchedDirection.equalsIgnoreCase(p.getPositionSide())).findFirst().orElseGet(() -> {
            if (matchedDirection != null) {
                return null;
            }
            for (ExchangeGateway.PositionSnapshot snapshot : snapshots) {
                if (symbol.equalsIgnoreCase(snapshot.symbol()) && snapshot.quantity() != null && snapshot.quantity().signum() != 0) {
                    String direction = "BOTH".equalsIgnoreCase(snapshot.side()) ? (snapshot.quantity().signum() >= 0 ? "LONG" : "SHORT") : normalizeProtectionSide(snapshot.side());
                    return positionMapper.selectOne(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credentialId).eq(QuantPosition::getSymbol, symbol).eq(QuantPosition::getPositionSide, direction));
                }
            }
            return null;
        });
    }

    private QuantReconcileRecord record(Long credentialId, String result, String detail) {
        QuantReconcileRecord record = new QuantReconcileRecord();
        record.setCredentialId(credentialId);
        record.setReconcileType("FULL");
        record.setResult(result);
        record.setDetailJson(detail);
        record.setReconciledAt(LocalDateTime.now());
        return record;
    }

    /**
     * A fresh account event invalidated an in-flight REST snapshot; safe to fetch again.
     */
    public static final class SnapshotSupersededException extends IllegalStateException {
        public SnapshotSupersededException() {
            this("Binance position snapshot superseded by newer account event");
        }

        public SnapshotSupersededException(String message) {
            super(message);
        }
    }

    private record ExchangeFacts(ExchangeGateway.AccountSnapshot account, List<ExchangeGateway.PositionSnapshot> positions, List<ExchangeGateway.OrderSnapshot> openOrders,
                                 List<ExchangeGateway.OrderSnapshot> history, Map<String, List<ExchangeGateway.TradeSnapshot>> trades,
                                 Map<String, Instant> fillCursorEnds, Set<String> fillHistoryBacklogSymbols,
                                 Map<String, List<ExchangeGateway.ProtectionOrderSnapshot>> protections,
                                 List<ExchangeGateway.IncomeSnapshot> income) {
    }

    private record CachedPositionSnapshot(long positionGeneration, long runtimeGeneration, Instant fetchedAt, List<ExchangeGateway.PositionSnapshot> positions) {
    }
}
