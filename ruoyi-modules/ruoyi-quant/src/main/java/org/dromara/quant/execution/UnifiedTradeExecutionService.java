package org.dromara.quant.execution;

import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.QuantityNormalizer;
import org.dromara.quant.risk.LiveTradingGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.function.Supplier;

/**
 * 统一交易执行引擎。顺序固定为：幂等抢占 -> 风控闸门 -> 参数标准化 -> 交易所执行 -> 本地订单账本。
 */
@Service
@Slf4j
public class UnifiedTradeExecutionService implements TradeExecutionPort {
    @Autowired
    private org.dromara.quant.decision.TradeDecisionLogService decisionLogs;

    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * LIVE 实盘安全闸门。
     */
    private final LiveTradingGate liveTradingGate;
    /**
     * 执行记录服务。
     */
    private final ExecutionRecordService executionRecordService;
    /**
     * 订单账本服务。
     */
    private final OrderLedgerService orderLedgerService;
    private final org.dromara.quant.risk.OpenCapacityService openCapacityService;
    private final org.dromara.quant.market.AccountPositionCache positionCache;
    private final org.dromara.quant.market.AccountBalanceCache balanceCache;
    private final TradingPreparationService preparationService;
    private volatile LiveOrderDispatcher liveDispatcher;
    private volatile StrategyOrderAcceptanceJournal acceptanceJournal;

    @Autowired
    public void setAcceptanceJournal(StrategyOrderAcceptanceJournal journal) {
        this.acceptanceJournal = java.util.Objects.requireNonNull(journal);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setLiveDispatcher(LiveOrderDispatcher dispatcher) {
        this.liveDispatcher = java.util.Objects.requireNonNull(dispatcher);
    }
    private volatile org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService runtimeLease;
    private volatile org.dromara.quant.runtime.coordination.StrategyCloseOwnershipGuard closeOwnershipGuard;

    @org.springframework.beans.factory.annotation.Autowired
    public void setCloseOwnershipGuard(org.dromara.quant.runtime.coordination.StrategyCloseOwnershipGuard guard) {
        this.closeOwnershipGuard = java.util.Objects.requireNonNull(guard);
    }

    /** Injected by Spring; direct non-Spring constructions remain fail-closed for LIVE strategy orders. */
    @org.springframework.beans.factory.annotation.Autowired
    public void setRuntimeLease(org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService runtimeLease) {
        this.runtimeLease = java.util.Objects.requireNonNull(runtimeLease);
    }



    /**
     * 创建 UnifiedTradeExecutionService 实例并初始化其依赖。
     */
    public UnifiedTradeExecutionService(ExchangeGateway gateway, LiveTradingGate liveTradingGate, ExecutionRecordService executionRecordService,
                                        OrderLedgerService orderLedgerService, org.dromara.quant.risk.OpenCapacityService openCapacityService,
                                        org.dromara.quant.market.AccountPositionCache positionCache, org.dromara.quant.market.AccountBalanceCache balanceCache, TradingPreparationService preparationService) {
        this.gateway = gateway;
        this.liveTradingGate = liveTradingGate;
        this.executionRecordService = executionRecordService;
        this.orderLedgerService = orderLedgerService;
        this.openCapacityService = openCapacityService;
        this.positionCache = positionCache;
        this.balanceCache = balanceCache;
        this.preparationService = preparationService;
    }

    /**
     * 判断当前运行模式是否会真实向交易所发送订单。
     */
    private static boolean sendsToExchange(String mode) {
        return "LIVE".equalsIgnoreCase(mode) || "TESTNET".equalsIgnoreCase(mode);
    }

    /**
     * 按交易对数量步进标准化保护单数量。
     */
    private static BigDecimal normalizeQuantity(BigDecimal value, ExchangeGateway.SymbolRule rule, String mode, String type) {
        return "PAPER".equalsIgnoreCase(mode) && rule == null ? value : QuantityNormalizer.quantity(value, rule, type);
    }

    /**
     * 按交易对规则标准化订单价格。
     */
    private static BigDecimal normalizePrice(BigDecimal value, ExchangeGateway.SymbolRule rule, String mode) {
        if (value == null) {
            return null;
        }
        return "PAPER".equalsIgnoreCase(mode) && rule == null ? value : QuantityNormalizer.price(value, rule);
    }

    /**
     * 校验执行幂等键不能为空。
     */
    private static void requireIdempotency(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缺少幂等键");
        }
    }

    /**
     * 执行统一开仓流程，并通过执行记录保证幂等。
     */
    @Override
    public ExecutionResult open(OpenPositionCommand command) {
        var prepared = new java.util.concurrent.atomic.AtomicReference<TradingPreparationService.Prepared>();
        int leverage = command.leverage() == null ? 1 : command.leverage();
        return execute("OPEN", command.credentialId(), command.strategyId(), command.strategyInstanceId(), command.credential(), command.symbol(), command.idempotencyKey(),
            command.runMode(), command.safety(), command.symbolRule(), () -> {
            if (sendsToExchange(command.runMode()) && preparationService.requireReady(command.credentialId(), command.symbol(), leverage, command.marginMode()) != prepared.get()) {
                throw new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("交易设置在执行准备期间变化，等待新的行情");
            }
        }, () -> {
            ExchangeGateway.SymbolRule rule = command.symbolRule();
            if (sendsToExchange(command.runMode())) {
                var token = preparationService.requireReady(command.credentialId(), command.symbol(), leverage, command.marginMode());
                if (token == null) { throw new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("交易设置未准备好"); }
                prepared.set(token);
                if (token.rule() != null) { rule = token.rule(); }
            }
            BigDecimal quantity = normalizeQuantity(command.quantity(), rule, command.runMode(), command.type());
            BigDecimal price = normalizePrice(command.price(), rule, command.runMode());
            if (price != null) {
                QuantityNormalizer.requireNotional(price, quantity, rule);
            }
            String positionSide = command.positionSide();
            if (sendsToExchange(command.runMode())) {
                positionSide = prepared.get().positionMode().hedgeMode() ? ("BUY".equalsIgnoreCase(command.side()) ? "LONG" : "SHORT") : "BOTH";
            }
            return new ExchangeGateway.OrderRequest(command.credential(), command.symbol(), command.side(), command.type(), quantity, price, false, positionSide,
                exchangeClientOrderId(command.idempotencyKey()), sendsToExchange(command.runMode()), command.validUntil());
        }, command);
    }

    /**
     * 执行减仓或全平流程。
     */
    @Override
    public ExecutionResult close(ClosePositionCommand command) {
        if (sendsToExchange(command.runMode()) && !("BOTH".equalsIgnoreCase(command.positionSide())
            || "LONG".equalsIgnoreCase(command.positionSide()) && "SELL".equalsIgnoreCase(command.side())
            || "SHORT".equalsIgnoreCase(command.positionSide()) && "BUY".equalsIgnoreCase(command.side()))) {
            throw new IllegalArgumentException("平仓方向不明确或与持仓方向不匹配，拒绝交易所请求");
        }
        return execute("CLOSE", command.credentialId(), command.strategyId(), command.strategyInstanceId(), command.credential(), command.symbol(), command.idempotencyKey(),
            command.runMode(), command.safety(), command.symbolRule(), () -> {
        }, () -> {
            ExchangeGateway.SymbolRule rule = command.symbolRule();
            if (sendsToExchange(command.runMode())) {
                ExchangeGateway.SymbolRule actual = gateway.queryTradingRule(command.credential(), command.symbol());
                if (actual != null) { rule = actual; }
            }
            BigDecimal quantity = normalizeQuantity(command.quantity(), rule, command.runMode(), command.type());
            BigDecimal price = normalizePrice(command.price(), rule, command.runMode());
            return new ExchangeGateway.OrderRequest(command.credential(), command.symbol(), command.side(), command.type(), quantity, price, true, command.positionSide(),
                exchangeClientOrderId(command.idempotencyKey()), sendsToExchange(command.runMode()));
        }, null);
    }

    /**
     * 将内部幂等键转换为 Binance 允许的客户端订单号。
     * 内部幂等键保留完整值，交易所订单号使用确定性短哈希，确保恢复查询仍可匹配。
     */
    static String exchangeClientOrderId(String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKey.length() < 36 && idempotencyKey.matches("[A-Za-z0-9._:/-]+")) {
            return idempotencyKey;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(idempotencyKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("Q");
            for (int i = 0; i < 17; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("无法生成交易所客户端订单号", ex);
        }
    }


    /**
     * 执行撤单流程。
     */
    @Override
    public ExecutionResult cancel(CancelOrderCommand command) {
        requireIdempotency(command.idempotencyKey());
        boolean live = "LIVE".equalsIgnoreCase(command.runMode());
        String targetClientId = command.clientOrderId();
        Long targetOrderId = command.orderId();
        // The execution record and dispatch ticket MUST identify the actual order being
        // cancelled, not a synthetic ID unrelated to that order. A missing client ID
        // is first resolved by a precise exchange query, before any side-effect.
        if (live && (targetClientId == null || targetClientId.isBlank())) {
            if (targetOrderId == null) {
                throw new IllegalArgumentException("LIVE 撤单缺少交易所订单或客户端标识");
            }
            var identity = gateway.queryOrder(command.credential(), command.symbol(), targetOrderId, null);
            if (identity == null || !targetOrderId.equals(identity.orderId())
                || identity.clientOrderId() == null || identity.clientOrderId().isBlank()) {
                throw new IllegalStateException("无法证明 LIVE 撤单的真实客户端订单标识");
            }
            targetClientId = identity.clientOrderId();
        }
        QuantExecutionRecordReservation reservation = reserve("CANCEL", command.credentialId(), null, null,
            command.symbol(), command.idempotencyKey(), targetClientId);
        if (!reservation.owner()) {
            if (reservation.result() == null || !reservation.result().accepted()) {
                throw new IllegalStateException("幂等取消请求仍处于未确认状态：" + command.idempotencyKey());
            }
            return reservation.result();
        }

        LiveOrderDispatcher.Ticket ticket = null;
        boolean attempted = false;
        String resolvedClientId = targetClientId;
        try {
            if (live) {
                ticket = requireLiveDispatcher().begin(command.credentialId(), command.symbol(),
                    command.idempotencyKey(), "CANCEL", resolvedClientId, null, null);
            }
            attempted = true;
            gateway.cancelOrder(new ExchangeGateway.CancelOrderRequest(command.credential(),
                command.symbol(), targetOrderId, resolvedClientId, sendsToExchange(command.runMode())));
            if (!live) {
                executionRecordService.accepted(reservation.record(), targetOrderId, "CANCEL_ACCEPTED");
                return new ExecutionResult(true, "CANCEL_ACCEPTED", targetOrderId, resolvedClientId);
            }
            // Binance accepting a CANCEL request is NOT proof that the order ended
            // unfilled. The order may be FILLED or PARTIALLY_FILLED by the time
            // cancellation reaches the exchange. Do not release the dispatch slot
            // until a precise terminal snapshot and local ledger are both durable.
            ExchangeGateway.OrderSnapshot actual = gateway.queryOrder(
                command.credential(), command.symbol(), targetOrderId, resolvedClientId);
            if (!verifiedTerminalCancel(actual, command.symbol(), targetOrderId, resolvedClientId)) {
                throw new IllegalStateException("LIVE 撤单没有可信的交易所终态，保留调度占用");
            }
            orderLedgerService.syncSnapshot(command.credentialId(), actual);
            executionRecordService.accepted(reservation.record(), actual.orderId(), actual.status());
            requireLiveDispatcher().acknowledged(ticket, actual.orderId());
            ticket = null;
            return new ExecutionResult(true, actual.status(), actual.orderId(), actual.clientOrderId());
        } catch (RuntimeException ex) {
            if (ticket != null) {
                try {
                    if (attempted && (ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException
                        || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException)) {
                        requireLiveDispatcher().notSent(ticket);
                    } else {
                        requireLiveDispatcher().unknown(ticket);
                    }
                } catch (RuntimeException dispatchEx) {
                    ex.addSuppressed(dispatchEx);
                }
            }
            if (attempted) {
                executionRecordService.markUnknown(reservation.record(), ex.getMessage());
            } else {
                executionRecordService.failed(reservation.record(), ex.getMessage());
            }
            throw ex;
        }
    }

    /** Only order TERMINAL states allow a LIVE cancel to release the account-symbol slot. */
    static boolean verifiedTerminalCancel(ExchangeGateway.OrderSnapshot actual, String symbol,
                                          Long orderId, String clientId) {
        return actual != null && actual.orderId() != null
            && (orderId == null || orderId.equals(actual.orderId()))
            && clientId != null && clientId.equals(actual.clientOrderId())
            && symbol != null && symbol.equalsIgnoreCase(actual.symbol())
            && java.util.Set.of("CANCELED", "EXPIRED", "FILLED", "REJECTED")
                .contains(actual.status() == null ? "" : actual.status().toUpperCase(java.util.Locale.ROOT))
            && actual.executedQuantity() != null
            && actual.executedQuantity().signum() >= 0;
    }

    /**
     * 执行统一交易流水：幂等、安全检查、标准化、交易所调用和账本落库。
     */
    private ExecutionResult execute(String operationType, Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.Credential credential, String symbol,
                                    String idempotencyKey, String runMode, ExecutionSafetyContext safety, ExchangeGateway.SymbolRule rule, Runnable beforeExchangeAction,
                                    Supplier<ExchangeGateway.OrderRequest> requestSupplier, OpenPositionCommand openCommand) {
        requireIdempotency(idempotencyKey);
        QuantExecutionRecordReservation reservation = reserve(operationType, credentialId, strategyId, strategyInstanceId, symbol, idempotencyKey, exchangeClientOrderId(idempotencyKey));
        if (!reservation.owner()) {
            if (reservation.result() == null || !reservation.result().accepted()) {
                throw new IllegalStateException("幂等交易请求仍处于未确认状态：" + idempotencyKey);
            }
            return reservation.result();
        }
        recordDecision(operationType, credentialId, strategyId, strategyInstanceId, symbol, idempotencyKey, runMode,
            "REQUESTED", "执行请求", null, null, null, null);
        ExchangeGateway.OrderRequest logRequest = null;
        ExchangeGateway.OrderResult exchangeResult = null;
        boolean exchangeAttempted = false;
        LiveOrderDispatcher.Ticket dispatchTicket = null;
        long startedNanos = System.nanoTime();
        org.dromara.quant.market.AccountBalanceSnapshot balance = null;
        Long preparationEpoch = null;
        try {
            if (openCommand != null) {
                requireFresh(openCommand);
                BigDecimal notional = openCommand.notional();
                if (notional == null) {
                    ExchangeGateway.PriceSnapshot snapshot = "TESTNET".equalsIgnoreCase(runMode) ? gateway.queryPrice(runMode, symbol) : gateway.queryPrice(symbol);
                    notional = snapshot == null || snapshot.markPrice() == null ? null : openCommand.quantity().multiply(snapshot.markPrice());
                }
                var positions = "PAPER".equalsIgnoreCase(runMode) ? null : positionCache.requirePositions(credentialId);
                requireFresh(openCommand);
                balance = "PAPER".equalsIgnoreCase(runMode) ? null : balanceCache.requireSnapshot(credentialId);
                openCapacityService.reserve(openCommand, notional, positions, balance);
            }
            checkLive(operationType, runMode, safety, credential, rule);
            if ("LIVE".equalsIgnoreCase(runMode) && strategyInstanceId != null) {
                var verifier = runtimeLease;
                if (verifier == null) {
                    throw new IllegalStateException("LIVE 策略执行缺少租约验证器");
                }
                preparationEpoch = verifier.requireCurrentEpoch(strategyInstanceId, credentialId, symbol);
            }
            ExchangeGateway.OrderRequest request = requestSupplier.get();
            verifyLiveCloseOwnership(operationType, credentialId, strategyId, strategyInstanceId, symbol,
                idempotencyKey, runMode, request);
            logRequest = request;
            recordDecision(operationType, credentialId, strategyId, strategyInstanceId, symbol, idempotencyKey, runMode,
                "REQUESTED", "执行参数已准备", request, null, null, null);
            beforeExchangeAction.run();
            if (openCommand != null) {
                requireFresh(openCommand);
                if (sendsToExchange(runMode)) {
                    positionCache.requirePositions(credentialId);
                    if (balanceCache.requireSnapshot(credentialId) != balance) {
                        throw new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("账户余额在开仓准备期间变化，等待新的行情重新评估");
                    }
                }
            }
            if ("PAPER".equalsIgnoreCase(runMode)) {
                // PAPER 没有外部交易副作用；直接写入确定性的 FILLED 执行事实，避免“已保留保证金但进程崩溃”产生无法判定的本地订单。
                exchangeResult = new ExchangeGateway.OrderResult(null, request.clientOrderId(), "FILLED");
            } else {
                if (openCommand != null) {
                    // 最后检查准备令牌，断线/外部配置变更不能靠旧设置放行。
                    beforeExchangeAction.run();
                    requireFresh(openCommand);
                    log.debug("开仓执行耗时 credentialId={} symbol={} preparationMillis={} eventAgeMillis={} remainingSignalMillis={}", credentialId, symbol,
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                        openCommand.signalEventTime() == null ? null : java.time.Duration.between(openCommand.signalEventTime(), java.time.Instant.now()).toMillis(),
                        openCommand.validUntil() == null ? null : java.time.Duration.between(java.time.Instant.now(), openCommand.validUntil()).toMillis());
                }
                verifyLiveCloseOwnership(operationType, credentialId, strategyId, strategyInstanceId,
                    symbol, idempotencyKey, runMode, request);
                if (preparationEpoch != null) {
                    // Reject requests prepared before lease expiry/takeover/reacquisition.
                    // This guards the entire preparation window, not only final token ownership.
                    long currentEpoch = runtimeLease.requireCurrentEpoch(strategyInstanceId, credentialId, symbol);
                    if (preparationEpoch.longValue() != currentEpoch) {
                        throw new IllegalStateException("LIVE 策略租约代次在下单准备期间变化，拒绝旧交易信号");
                    }
                }
                if ("LIVE".equalsIgnoreCase(runMode)) {
                    dispatchTicket = requireLiveDispatcher().begin(credentialId, symbol, idempotencyKey,
                        operationType, request.clientOrderId(), strategyInstanceId, preparationEpoch);
                    // The durable guard transaction itself may block behind another
                    // node. Re-check the exact lease epoch AFTER acquiring the guard
                    // and BEFORE any network request. If ownership changed, the
                    // request is provably NOT SENT and we can free our own guard.
                    if (preparationEpoch != null) {
                        try {
                            if (runtimeLease.requireCurrentEpoch(strategyInstanceId, credentialId, symbol)
                                != preparationEpoch.longValue()) {
                                throw new IllegalStateException("LIVE 订单调度等待期间租约代次变化，拒绝旧节点下单");
                            }
                        } catch (RuntimeException stale) {
                            try {
                                requireLiveDispatcher().notSent(dispatchTicket);
                                dispatchTicket = null;
                            } catch (RuntimeException releaseFailure) {
                                stale.addSuppressed(releaseFailure);
                                // Leave the ticket occupied; outcome of database release is unknown.
                                dispatchTicket = null;
                            }
                            throw stale;
                        }
                    }
                }
                exchangeAttempted = true;
                long orderStarted = System.nanoTime();
                exchangeResult = gateway.placeOrder(request);
                log.debug("交易所下单耗时 credentialId={} symbol={} elapsedMillis={}", credentialId, symbol,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - orderStarted));
            }
            executionRecordService.accepted(reservation.record(), exchangeResult.orderId(), exchangeResult.status());
            recordDecision(operationType, credentialId, strategyId, strategyInstanceId, symbol, idempotencyKey, runMode,
                "PARTIALLY_FILLED".equals(exchangeResult.status()) ? "PARTIALLY_FILLED" : "ACCEPTED", "交易所已受理，等待成交事实", request, exchangeResult, null, null);
            try {
                if ("LIVE".equalsIgnoreCase(runMode) && strategyId != null && strategyInstanceId != null) {
                    if (acceptanceJournal == null) {
                        throw new IllegalStateException("Missing transactional strategy order provenance journal");
                    }
                    acceptanceJournal.persist(credentialId, strategyId, strategyInstanceId,
                        runMode, operationType, request, exchangeResult);
                } else {
                    orderLedgerService.upsert(credentialId, strategyId, strategyInstanceId, request, exchangeResult);
                }
            } catch (RuntimeException ledgerEx) {
                // 交易所已经返回订单结果；本地账本失败不能把执行事实改写为 FAILED。由恢复/对账补齐账本。
                throw ledgerEx;
            }
            if (dispatchTicket != null) {
                requireLiveDispatcher().acknowledged(dispatchTicket, exchangeResult.orderId());
                dispatchTicket = null;
            }
            return new ExecutionResult(true, exchangeResult.status(), exchangeResult.orderId(), exchangeResult.clientOrderId());
        } catch (RuntimeException ex) {
            if (dispatchTicket != null) {
                try {
                    if (exchangeResult == null && exchangeAttempted
                        && (ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException
                            || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException)) {
                        requireLiveDispatcher().notSent(dispatchTicket);
                    } else {
                        requireLiveDispatcher().unknown(dispatchTicket);
                    }
                } catch (RuntimeException dispatchEx) {
                    ex.addSuppressed(dispatchEx); // Unresolved guard remains blocked until verified recovery.
                }
            }
            String logStatus = exchangeResult != null ? "ACCEPTED" : exchangeAttempted
                && !(ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException
                    || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException) ? "UNKNOWN" : "FAILED";
            recordDecision(operationType, credentialId, strategyId, strategyInstanceId, symbol, idempotencyKey, runMode,
                logStatus, org.dromara.quant.decision.TradeDecisionLogService.safeReason(ex), logRequest, exchangeResult,
                ex == null ? null : "EXECUTION_FAILURE", null);
            if (exchangeResult == null && (ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException
                || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.OrderNotSentException)) {
                executionRecordService.failed(reservation.record(), ex.getMessage());
                if (openCommand != null) { openCapacityService.releaseFailed(credentialId, idempotencyKey); }
            } else if (exchangeResult == null && exchangeAttempted) {
                // 下单请求已经发往交易所但没有拿到确定响应，禁止标记 FAILED，避免后续重复下单。
                executionRecordService.markUnknown(reservation.record(), ex.getMessage());
            } else if (exchangeResult == null) {
                executionRecordService.failed(reservation.record(), ex.getMessage());
                if (openCommand != null) { openCapacityService.releaseFailed(credentialId, idempotencyKey); }
            }
            throw ex;
        }
    }

    private LiveOrderDispatcher requireLiveDispatcher() {
        LiveOrderDispatcher value = liveDispatcher;
        if (value == null) {
            throw new IllegalStateException("LIVE 订单调度器不可用，拒绝直接调用交易所");
        }
        return value;
    }

    /** State is re-read directly before the exchange call; stale strategy shares fail closed. */
    private void verifyLiveCloseOwnership(String operation, Long credentialId, Long strategyId,
                                          Long instanceId, String symbol, String idempotencyKey,
                                          String runMode, ExchangeGateway.OrderRequest request) {
        if (!"LIVE".equalsIgnoreCase(runMode) || !"CLOSE".equals(operation) || instanceId == null) {
            return;
        }
        var guard = closeOwnershipGuard;
        if (guard == null) {
            throw new IllegalStateException("LIVE 策略平仓缺少持仓归属验证器");
        }
        guard.requireOwnedClose(credentialId, strategyId, instanceId, symbol,
            request.side(), request.quantity(), idempotencyKey);
    }

    private void recordDecision(String operation, Long credentialId, Long strategyId, Long instanceId, String symbol,
                                String executionKey, String runMode, String status, String reason,
                                ExchangeGateway.OrderRequest request, ExchangeGateway.OrderResult result, String reasonCode, java.time.Instant tradeTime) {
        if (decisionLogs == null) { return; }
        decisionLogs.record(org.dromara.quant.decision.DecisionLogEvent.builder()
            .eventKey(org.dromara.quant.decision.TradeDecisionLogRepository.executionKey(credentialId, executionKey))
            .recordType("EXECUTION").credentialId(credentialId).strategyId(strategyId).strategyInstanceId(instanceId)
            .symbol(symbol).runMode(runMode).operation(operation).status(status)
            .direction(request == null ? null : ("OPEN".equals(operation) == "BUY".equals(request.side()) ? "LONG" : "SHORT"))
            .quantity(request == null ? null : request.quantity()).price(request == null ? null : request.price())
            .reasonCode(reasonCode == null ? "EXECUTION_RESULT" : reasonCode).reason(reason).idempotencyKey(executionKey)
            .orderId(result == null ? null : result.orderId()).clientOrderId(result == null ? exchangeClientOrderId(executionKey) : result.clientOrderId())
            .eventTime(java.time.Instant.now()).tradeTime(tradeTime).build());
    }

    private static void requireFresh(OpenPositionCommand command) {
        if (command.validUntil() != null && !java.time.Instant.now().isBefore(command.validUntil())) {
            throw new org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException("开仓信号已过期，禁止按旧点位追单");
        }
    }

    /**
     * 抢占执行幂等记录并判断当前调用是否拥有执行权。
     */
    private QuantExecutionRecordReservation reserve(String operationType, Long credentialId, Long strategyId, Long strategyInstanceId, String symbol, String idempotencyKey,
                                                    String clientOrderId) {
        if (credentialId == null) {
            throw new IllegalArgumentException("缺少凭证ID");
        }
        var existing = executionRecordService.find(credentialId, idempotencyKey);
        if (existing != null) {
            return existingReservation(existing);
        }
        var record = executionRecordService.tryCreatePending(credentialId, strategyId, strategyInstanceId, operationType, symbol, idempotencyKey, clientOrderId);
        if (record != null) {
            return new QuantExecutionRecordReservation(record, true, null);
        }
        existing = executionRecordService.find(credentialId, idempotencyKey);
        if (existing == null) {
            throw new IllegalStateException("无法创建执行幂等记录");
        }
        return existingReservation(existing);
    }

    /**
     * 根据既有执行记录构造可复用的执行结果。
     */
    private QuantExecutionRecordReservation existingReservation(org.dromara.quant.execution.domain.QuantExecutionRecord existing) {
        if ("PENDING".equals(existing.getExecutionStatus()) || "UNKNOWN".equals(existing.getExecutionStatus())) {
            throw new IllegalStateException("幂等请求正在等待交易所/恢复流程确认：" + existing.getIdempotencyKey());
        }
        if ("FAILED".equals(existing.getExecutionStatus())) {
            throw new IllegalStateException("幂等请求已失败，请使用新的幂等键重试：" + existing.getIdempotencyKey());
        }
        boolean accepted = "CONFIRMED".equals(existing.getExecutionStatus()) || "RECOVERED".equals(existing.getExecutionStatus());
        String status = existing.getExchangeOrderStatus() == null ? existing.getExecutionStatus() : existing.getExchangeOrderStatus();
        return new QuantExecutionRecordReservation(existing, false, new ExecutionResult(accepted, status, existing.getExchangeOrderId(), existing.getClientOrderId()));
    }

    /**
     * 在 LIVE 模式下执行实盘安全闸门检查。
     */
    private void checkLive(String operationType, String runMode, ExecutionSafetyContext safety, ExchangeGateway.Credential credential, ExchangeGateway.SymbolRule rule) {
        if (!"LIVE".equalsIgnoreCase(runMode)) {
            return;
        }
        boolean closing = "CLOSE".equalsIgnoreCase(operationType);
        if (credential == null || safety == null || !closing && rule == null) {
            throw new LiveTradingGate.LiveTradingBlockedException();
        }
        // 减仓不依赖本地导入规则；close 会读取交易所规则并验证数量，账户权限与授权仍需通过。
        // 系统新开仓开关不能阻止既有仓位的明确减仓。
        liveTradingGate.check(new LiveTradingGate.Checkpoint(closing || safety.systemLiveEnabled(), safety.accountLiveEnabled(), safety.futuresTradePermission(),
            safety.withdrawalDisabled(), safety.connectivityVerified(), safety.accountAuthorized(), closing || safety.riskConfigured(),
            closing || rule.minNotional() != null && rule.minNotional().signum() > 0, closing || safety.approvedAndNotTripped()));
    }

    /**
     * 统一执行幂等抢占结果。
     *
     * @param record 执行幂等记录
     * @param owner  当前调用是否拥有执行权
     * @param result 对账结果
     */
    private record QuantExecutionRecordReservation(org.dromara.quant.execution.domain.QuantExecutionRecord record, boolean owner, ExecutionResult result) {
    }
}




