package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.quant.account.domain.QuantAccountSnapshot;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantAccountSnapshotMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
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
import org.dromara.quant.runtime.service.StrategyPositionStateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 交易所事实与本地交易账本的最终一致性入口。
 * <p>订单使用历史订单接口而非仅当前挂单接口；成交按最近窗口持续同步，避免成交后从 openOrders 中消失导致本地状态停在 NEW。
 */
@Service
public class TradingReconciliationService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.dromara.quant.decision.TradeDecisionLogService decisionLogs;

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
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.locks.ReentrantLock> positionLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.concurrent.locks.ReentrantLock positionLock(Long credentialId) {
        return positionLocks.computeIfAbsent(credentialId, ignored -> new java.util.concurrent.locks.ReentrantLock());
    }
    private volatile org.dromara.quant.execution.StrategyFillAllocationService fillAllocations;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private LivePositionFreshness positionFreshness;
    private long positionGeneration(Long credentialId) {
        return positionFreshness == null ? 0L : positionFreshness.generation(credentialId);
    }
    private void markPositionVerified(Long credentialId, long generation) {
        if (positionFreshness != null) {
            positionFreshness.verifiedIfUnchanged(credentialId, generation);
        }
    }


    @org.springframework.beans.factory.annotation.Autowired
    public void setFillAllocations(org.dromara.quant.execution.StrategyFillAllocationService service) {
        this.fillAllocations = java.util.Objects.requireNonNull(service);
    }

    /**
     * 创建 TradingReconciliationService 实例并初始化其依赖。
     */
    public TradingReconciliationService(ExchangeGateway gateway, QuantAccountSnapshotMapper accountSnapshotMapper, QuantPositionMapper positionMapper,
                                        QuantOrderMapper orderMapper, QuantTradeMapper tradeMapper, QuantProtectionOrderMapper protectionOrderMapper,
                                        QuantFinancialEventMapper financialEventMapper, QuantReconcileRecordMapper reconcileMapper,
                                        ReconciliationFailureRecorder failureRecorder, StrategyPositionStateService strategyPositionStateService,
                                        PlatformTransactionManager transactionManager) {
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
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(30);
    }

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

    /**
     * Startup/triggered position-only refresh. No historical orders or fills
     * are required to persist an authenticated full account position snapshot.
     * Never treat an unavailable exchange response as an empty account.
     */
    public void refreshPositionsOnly(QuantCredential credential) {
        var accountLock = positionLock(credential.getId());
        accountLock.lock();
        try {
            try {
                long positionGeneration = positionGeneration(credential.getId());
                ExchangeGateway.Credential exchangeCredential = new ExchangeGateway.Credential(
                    credential.getApiKey(), credential.getSecretKey(),
                    credential.getSignatureType(), credential.getRunMode());
                var account = java.util.Objects.requireNonNull(gateway.queryAccount(exchangeCredential),
                    "Binance account snapshot missing");
                var positions = List.copyOf(java.util.Objects.requireNonNull(
                    gateway.queryPositions(exchangeCredential, null),
                    "Binance full position snapshot missing"));
                transaction.executeWithoutResult(status -> {
                    syncAccount(credential, account);
                    syncPositions(credential, positions);
                });
                markPositionVerified(credential.getId(), positionGeneration);
            } catch (RuntimeException ex) {
                failureRecorder.record(credential.getId(), ex.getMessage());
                throw ex;
            }
        } finally {
            accountLock.unlock();
        }
    }

    /**
     * 对单个交易凭证执行完整事实对账。
     */
    /**
     * Query Binance outside of a transaction; missing facts must not partially overwrite
     * account positions, orders or protections.
     */
    public void reconcileCredential(QuantCredential credential, int historyHours) {
        var accountLock = positionLock(credential.getId());
        accountLock.lock();
        try {
            try {
                long positionGeneration = positionGeneration(credential.getId());
                ExchangeGateway.Credential exchangeCredential = new ExchangeGateway.Credential(
                    credential.getApiKey(), credential.getSecretKey(),
                    credential.getSignatureType(), credential.getRunMode());
                // Collect a complete account position snapshot before any DB writes.
                // Commit it independently BEFORE querying order/fill/income history:
                // a failing history endpoint must not keep already-closed Binance
                // positions counted as open indefinitely.
                var account = java.util.Objects.requireNonNull(
                    gateway.queryAccount(exchangeCredential), "Binance account snapshot missing");
                var positions = List.copyOf(java.util.Objects.requireNonNull(
                    gateway.queryPositions(exchangeCredential, null), "Binance full position snapshot missing"));
                transaction.executeWithoutResult(status -> {
                    syncAccount(credential, account);
                    syncPositions(credential, positions);
                });
                markPositionVerified(credential.getId(), positionGeneration);
                ExchangeFacts facts = collectExchangeFacts(credential, historyHours,
                    exchangeCredential, account, positions);
                transaction.executeWithoutResult(status -> applyExchangeFacts(credential, facts));
            } catch (RuntimeException ex) {
                failureRecorder.record(credential.getId(), ex.getMessage());
                throw ex;
            }
        } finally {
            accountLock.unlock();
        }
    }

    private record ExchangeFacts(ExchangeGateway.AccountSnapshot account,
                                 List<ExchangeGateway.PositionSnapshot> positions,
                                 List<ExchangeGateway.OrderSnapshot> openOrders,
                                 List<ExchangeGateway.OrderSnapshot> history,
                                 Map<String, List<ExchangeGateway.TradeSnapshot>> trades,
                                 Map<String, List<ExchangeGateway.ProtectionOrderSnapshot>> protections,
                                 List<ExchangeGateway.IncomeSnapshot> income) {}

    private ExchangeFacts collectExchangeFacts(QuantCredential credential, int historyHours,
                                              ExchangeGateway.Credential c,
                                              ExchangeGateway.AccountSnapshot account,
                                              List<ExchangeGateway.PositionSnapshot> positions) {
        LocalDateTime now = LocalDateTime.now();
        ExchangeGateway.TimeRange range = new ExchangeGateway.TimeRange(
            now.minusHours(Math.max(1, historyHours)).toInstant(ZoneOffset.UTC),
            now.toInstant(ZoneOffset.UTC));
        var openOrders = List.copyOf(gateway.queryOrders(c, null));
        var history = List.copyOf(gateway.queryOrderHistory(c, null, range, 1000L));
        Set<String> symbols = new HashSet<>();
        positions.forEach(p -> addSymbol(symbols, p.symbol()));
        history.forEach(o -> addSymbol(symbols, o.symbol()));
        openOrders.forEach(o -> addSymbol(symbols, o.symbol()));
        Map<String, List<ExchangeGateway.TradeSnapshot>> trades = new HashMap<>();
        Map<String, List<ExchangeGateway.ProtectionOrderSnapshot>> protections = new HashMap<>();
        for (String symbol : symbols) {
            trades.put(symbol, List.copyOf(gateway.queryTrades(c, symbol, range)));
            protections.put(symbol, List.copyOf(gateway.queryProtectionOrders(c, symbol)));
        }
        var income = List.copyOf(gateway.queryIncomeHistory(c, null, null, range, 1000L));
        return new ExchangeFacts(account, positions, openOrders, history, trades, protections, income);
    }

    /** Only database writes are permitted inside this short transaction. */
    private void applyExchangeFacts(QuantCredential credential, ExchangeFacts facts) {
        syncOrders(credential, facts.history());
        syncOrders(credential, facts.openOrders());
        int unattributedFills = 0;
        for (var entry : facts.trades().entrySet()) {
            unattributedFills += syncTrades(credential, entry.getValue());
        }
        int unmatchedProtections = 0;
        for (var entry : facts.protections().entrySet()) {
            unmatchedProtections += syncProtectionOrders(credential, entry.getKey(), facts.positions(), entry.getValue());
        }
        syncFinancialEvents(credential, facts.income());
        if (unmatchedProtections > 0 || unattributedFills > 0) {
            // Neither unowned protective orders nor unmatched fills may be
            // reported as a completely reconciled LIVE strategy account.
            reconcileMapper.insert(record(credential.getId(), "DEGRADED",
                "{\"unmatchedLiveProtections\":" + unmatchedProtections
                    + ",\"unattributedFills\":" + unattributedFills + "}"));
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

        List<QuantPosition> locals =
            positionMapper.selectList(new LambdaQueryWrapper<QuantPosition>().eq(QuantPosition::getCredentialId, credential.getId()).gt(QuantPosition::getQuantity,
                BigDecimal.ZERO));
        for (QuantPosition local : locals) {
            String key = local.getSymbol().toUpperCase() + ":" + local.getPositionSide().toUpperCase();
            if (!activeKeys.contains(key)) {
                local.setQuantity(BigDecimal.ZERO);
                local.setUnrealizedPnl(BigDecimal.ZERO);
                local.setUpdatedAt(LocalDateTime.now());
                positionMapper.updateById(local);
                strategyPositionStateService.markClosedByPositionId(local.getId());
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
                orderMapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credential.getId()).eq(QuantOrder::getExchangeOrderId, s.orderId()));
            if (entity == null) {
                entity = orderMapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credential.getId()).eq(QuantOrder::getClientOrderId,
                    clientOrderId));
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
    private int syncTrades(QuantCredential credential, List<ExchangeGateway.TradeSnapshot> snapshots) {
        int unattributed = 0;
        for (ExchangeGateway.TradeSnapshot s : snapshots) {
            if (decisionLogs != null) { decisionLogs.trade(credential.getId(), s); }
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
                orderMapper.selectOne(new LambdaQueryWrapper<QuantOrder>().eq(QuantOrder::getCredentialId, credential.getId()).eq(QuantOrder::getExchangeOrderId, s.orderId()));
            if (sourceOrder != null) {
                entity.setStrategyId(sourceOrder.getStrategyId());
                entity.setStrategyInstanceId(sourceOrder.getStrategyInstanceId());
                if ("LIVE".equalsIgnoreCase(credential.getRunMode())
                    && sourceOrder.getStrategyInstanceId() != null) {
                    var allocations = fillAllocations;
                    if (allocations == null) {
                        throw new IllegalStateException("LIVE 策略成交分配账本不可用，拒绝不完整对账");
                    }
                    allocations.allocate(s, sourceOrder, credential.getId());
                }
            }
            if ("LIVE".equalsIgnoreCase(credential.getRunMode())
                && (sourceOrder == null || sourceOrder.getStrategyInstanceId() == null)) {
                // May be an intentional manual trade; preserve it, but never assign
                // to an arbitrary strategy or silently report full reconciliation.
                unattributed++;
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
        return unattributed;
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
}
