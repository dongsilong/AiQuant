package org.dromara.quant.execution;

import org.dromara.quant.runtime.DualHedgePositionCycleKey;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Locale;
import java.util.Objects;

/**
 * Durable single-writer guard for LIVE account+symbol Binance requests.
 *
 * <p>Commit the intent and exclusive guard BEFORE any network call. Never hold a
 * database transaction or row lock during Binance HTTP. Unknown outcomes keep
 * the guard occupied indefinitely rather than allowing a timed stale takeover.
 * The manual/verified recovery path must reconcile the exact exchange order
 * before lifting a blocked account+symbol.
 *
 * <p>This is a conservative cross-node dispatch gate, not exchange-enforced fencing:
 * it cannot recall an HTTP request that has already left the process.
 */
@Service
public class LiveOrderDispatcher {
    private final LiveOrderDispatchMapper mapper;
    private final TransactionTemplate transaction;
    private volatile ApplicationEventPublisher eventPublisher;

    public LiveOrderDispatcher(LiveOrderDispatchMapper mapper, PlatformTransactionManager manager) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(5);
    }

    @Autowired(required = false)
    public void setEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * Both ticket creation and exclusive guard claim commit atomically.
     * The durable slot is never auto-freed by process death, lease expiry or time.
     */
    public Ticket begin(Long credentialId, String symbol, String key, String operation, String clientOrderId, Long instanceId, Long epoch) {
        if ("CONFIG_SETTINGS".equals(operation)) {
            throw new IllegalArgumentException("配置操作必须通过 beginConfiguration 核实全局参数契约");
        }
        return reserve(credentialId, symbol, key, operation, clientOrderId, instanceId, epoch, null, null, null, null, null, null);
    }

    /**
     * 获取账户交易配置派发票据，使其与订单操作使用同一串行队列。
     */
    public Ticket beginConfiguration(Long credentialId, String symbol, String key, String marginMode, int leverage) {
        if (!java.util.Set.of("ISOLATED", "CROSSED").contains(marginMode) || leverage < 1 || leverage > 125) {
            throw new IllegalArgumentException("LIVE Binance 配置范围或保证金模式无效");
        }
        return reserve(credentialId, symbol, key, "CONFIG_SETTINGS", "SETTINGS:" + marginMode + ":" + leverage, null, null, marginMode, leverage, null, null, null, null);
    }

    /**
     * Proof-carrying LIVE strategy reservation. This commits immutable
     * OPEN/CLOSE and logical LONG/SHORT provenance with the dispatch ticket.
     */
    public Ticket beginStrategy(Long credentialId, String symbol, String key, String operation, String clientOrderId, Long strategyId, Long instanceId, Long epoch,
                                String positionSide, String exchangeSide) {
        return beginStrategy(credentialId, symbol, key, operation, clientOrderId, strategyId, instanceId, epoch,
            positionSide, exchangeSide, null);
    }

    /**
     * 双开首次入场在持仓状态尚未创建时，使用轮次派生的不可变周期键。
     */
    public Ticket beginStrategy(Long credentialId, String symbol, String key, String operation, String clientOrderId, Long strategyId, Long instanceId, Long epoch,
                                String positionSide, String exchangeSide, String positionCycleKey) {
        String normalizedOperation = operation == null ? null : operation.toUpperCase(Locale.ROOT);
        String normalizedPositionSide = positionSide == null ? null : positionSide.toUpperCase(Locale.ROOT);
        String normalizedExchangeSide = exchangeSide == null ? null : exchangeSide.toUpperCase(Locale.ROOT);
        if ("BOTH".equals(normalizedPositionSide)) {
            // Binance one-way mode uses BOTH in the exchange request. The
            // strategy attribution ledger records the logical LONG/SHORT side.
            normalizedPositionSide = logicalPositionSide(normalizedOperation, normalizedExchangeSide);
        }
        boolean directionMatches = (("LONG".equals(normalizedPositionSide)) == "OPEN".equals(normalizedOperation)) ? "BUY".equals(normalizedExchangeSide) : "SELL".equals(normalizedExchangeSide);
        if (strategyId == null || strategyId <= 0 || instanceId == null || instanceId <= 0 || epoch == null || epoch <= 0 || normalizedOperation == null || normalizedPositionSide == null || normalizedExchangeSide == null ||
            !java.util.Set.of("OPEN", "CLOSE").contains(normalizedOperation) ||
            !java.util.Set.of("LONG", "SHORT").contains(normalizedPositionSide) || !java.util.Set.of("BUY", "SELL").contains(normalizedExchangeSide) || !directionMatches) {
            throw new IllegalArgumentException("未经验证的策略订单意图不能发送");
        }
        if (positionCycleKey != null && (!"OPEN".equals(normalizedOperation)
            || !positionCycleKey.equals(DualHedgePositionCycleKey.fromOpenIdempotencyKey(key))
            || !positionCycleKey.equals(DualHedgePositionCycleKey.of(
                key.substring(0, key.lastIndexOf(':', key.lastIndexOf(':') - 1)), normalizedPositionSide)))) {
            throw new IllegalArgumentException("双开入场周期键与轮次幂等键或持仓方向不匹配");
        }
        return reserve(credentialId, symbol, key, normalizedOperation, clientOrderId, instanceId, epoch, null, null,
            strategyId, normalizedPositionSide, normalizedExchangeSide, positionCycleKey);
    }

    private static String logicalPositionSide(String operation, String exchangeSide) {
        if ("OPEN".equals(operation)) {
            return "BUY".equals(exchangeSide) ? "LONG" : "SHORT";
        }
        if ("CLOSE".equals(operation)) {
            return "SELL".equals(exchangeSide) ? "LONG" : "SHORT";
        }
        return null;
    }

    private Ticket reserve(Long credentialId, String symbol, String key, String operation, String clientOrderId, Long instanceId, Long epoch, String marginMode, Integer leverage
        , Long strategyId, String positionSide, String exchangeSide, String positionCycleKey) {
        if (credentialId == null || credentialId <= 0 || symbol == null || symbol.isBlank() || key == null || key.isBlank() || operation == null || operation.isBlank() || clientOrderId == null || clientOrderId.isBlank() || instanceId != null && (epoch == null || epoch <= 0)) {
            throw new IllegalArgumentException("LIVE dispatch 缺少可靠的订单或租约标识");
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        try {
            return transaction.execute(status -> {
                mapper.ensureGuard(credentialId, normalized);
                int claimed = instanceId == null ? mapper.claim(credentialId, normalized, key) : mapper.claimWithLease(credentialId, normalized, key, instanceId, epoch);
                if (claimed != 1) {
                    boolean slotOccupied = mapper.activeDispatch(credentialId, normalized) != null;
                    String reason = slotOccupied ? "同账户交易对存在未完成交易所操作，订单未发送"
                        : "策略租约已过期或转移，订单未发送";
                    throw new DispatchBlockedException(reason);
                }
                if (marginMode != null) {
                    mapper.ensureSettingsIntent(credentialId, normalized, marginMode, leverage);
                    if (mapper.matchingSettingsIntent(credentialId, normalized, marginMode, leverage) != 1L) {
                        throw new IllegalStateException("同账户交易对存在冲突的保证金/杠杆配置契约，拒绝更改交易所");
                    }
                }
                if (mapper.insertTicket(credentialId, normalized, key, operation, clientOrderId, instanceId, epoch) != 1) {
                    throw new IllegalStateException("LIVE dispatch 发送票据落库失败");
                }
                if (strategyId != null) {
                    int intentInserted = positionCycleKey == null
                        ? mapper.insertPreDispatchIntent(credentialId, normalized, key, clientOrderId, strategyId, instanceId, operation, positionSide, exchangeSide, epoch)
                        : mapper.insertPreDispatchIntentWithCycle(credentialId, normalized, key, clientOrderId, strategyId, instanceId,
                            operation, positionSide, exchangeSide, epoch, positionCycleKey);
                    if (intentInserted != 1) {
                        throw new IllegalStateException("LIVE 发送前的策略意图尚未持久化");
                    }
                }
                return new Ticket(credentialId, normalized, key);
            });
        } catch (DispatchBlockedException blocked) {
            // TransactionTemplate has rolled back before this callback; never perform exchange IO under the DB transaction.
            ApplicationEventPublisher publisher = eventPublisher;
            if (publisher != null) {
                publisher.publishEvent(new DispatchBlockedEvent(credentialId, normalized, operation));
            }
            throw blocked;
        }
    }

    /**
     * Acknowledge only after the exchange acceptance AND local order ledger are durable.
     */
    public void acknowledged(Ticket ticket, Long exchangeOrderId) {
        if (ticket == null || exchangeOrderId == null || exchangeOrderId <= 0) {
            throw new IllegalStateException("LIVE 订单没有可信交易所订单 ID，必须保持发送票据占用");
        }
        transaction.executeWithoutResult(status -> {
            if (mapper.acknowledge(ticket.credentialId(), ticket.key(), exchangeOrderId) != 1 || mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("LIVE 订单已发出，但无法安全释放调度锁，等待人工核对");
            }
        });
    }

    /**
     * Account setup has no Binance order ID. The caller may invoke this ONLY
     * after BOTH changeMarginType and changeInitialLeverage return successfully.
     * No TTL takeover: if process/network outcome is uncertain, leave UNKNOWN.
     */
    public void configurationApplied(Ticket ticket) {
        if (ticket == null) {
            throw new IllegalArgumentException("缺少配置变更发送票据");
        }
        // Persist the confirmed exchange outcome in its own transaction before releasing
        // the guard. If the process crashes between these commits, CONFIG_APPLIED is durable
        // and recovery can independently verify Binance state without racing an in-flight call.
        transaction.executeWithoutResult(status -> {
            if (mapper.acknowledgeConfiguration(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("账户配置成功结果无法持久化，保持该交易对调度阻断");
            }
        });
        transaction.executeWithoutResult(status -> {
            if (mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("账户配置已确认，但调度锁未能安全释放，保持该交易对调度阻断");
            }
        });
    }

    /**
     * Check the immutable account-symbol configuration intent before issuing any
     * recovery write to Binance. A ticket alone is not authorization to change settings.
     */
    public boolean matchesConfigurationIntent(Long credentialId, String symbol, String marginMode, int leverage) {
        String normalizedSymbol = symbol == null ? null : symbol.toUpperCase(Locale.ROOT);
        String normalizedMode = marginMode == null ? null : marginMode.toUpperCase(Locale.ROOT);
        if (credentialId == null || credentialId <= 0 || normalizedSymbol == null || normalizedSymbol.isBlank()
            || normalizedMode == null || !java.util.Set.of("ISOLATED", "CROSSED").contains(normalizedMode)
            || leverage < 1 || leverage > 125) {
            return false;
        }
        return mapper.matchingSettingsIntent(credentialId, normalizedSymbol, normalizedMode, leverage) == 1L;
    }

    /**
     * Complete a CONFIG_SETTINGS ticket only after the caller has reconciled and
     * independently verified both persisted desired settings. The exact durable
     * intent, synthetic ticket identity and active guard must all match.
     */
    public boolean recoveredConfigurationFromExchange(Long credentialId, String symbol, String key, String clientOrderId, String marginMode, int leverage) {
        String normalizedSymbol = symbol == null ? null : symbol.toUpperCase(Locale.ROOT);
        String normalizedMode = marginMode == null ? null : marginMode.toUpperCase(Locale.ROOT);
        if (credentialId == null || credentialId <= 0 || normalizedSymbol == null || normalizedSymbol.isBlank()
            || key == null || key.isBlank() || normalizedMode == null
            || !java.util.Set.of("ISOLATED", "CROSSED").contains(normalizedMode) || leverage < 1 || leverage > 125
            || !("SETTINGS:" + normalizedMode + ":" + leverage).equals(clientOrderId)) {
            throw new IllegalArgumentException("缺少精确的目标配置标识，无法恢复配置变更发送");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (mapper.recoverConfiguration(credentialId, normalizedSymbol, key, clientOrderId, normalizedMode, leverage) != 1) {
                return false;
            }
            if (mapper.release(credentialId, normalizedSymbol, key) != 1) {
                throw new IllegalStateException("配置事实已验证，但持久化发送保护条件不匹配");
            }
            return true;
        }));
    }

    /**
     * Release an obsolete protection-create ticket only after the protection service
     * has independently verified the local cycle and exchange account are flat and idle.
     * This records business obsolescence, not a claim that Binance rejected or canceled it.
     */
    public boolean supersedeProtectionCreate(Long credentialId, String symbol, String key, String clientOrderId) {
        if (credentialId == null || credentialId <= 0 || symbol == null || symbol.isBlank()
            || key == null || key.isBlank() || clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalArgumentException("缺少精确标识，无法替代保护单票据");
        }
        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (mapper.supersedeProtectionCreate(credentialId, normalizedSymbol, key, clientOrderId) != 1) {
                return false;
            }
            if (mapper.release(credentialId, normalizedSymbol, key) != 1) {
                throw new IllegalStateException("保护单票据已被替代，但持久化发送保护条件不匹配");
            }
            return true;
        }));
    }

    /**
     * Reserved for a provably NOT-SENT result. All timeouts are UNKNOWN instead.
     */
    public void notSent(Ticket ticket) {
        transaction.executeWithoutResult(status -> {
            if (mapper.notSent(ticket.credentialId(), ticket.key()) != 1 || mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("LIVE 未发送票据无法安全解除调度占用");
            }
        });
    }

    /**
     * 仅在两次完整交易所核验均确认 OPEN 不存在后，终结对应 LIVE 票据。
     * 没有对应票据且账户交易对锁也未被该幂等键占用时，视为无需释放；
     * 孤立锁、身份不匹配或其他状态均保持阻断。
     */
    public boolean verifiedNotExecuted(Long credentialId, String symbol, String key, String clientOrderId, Long instanceId,
                                        java.util.function.BooleanSupplier finalizeExecution) {
        if (credentialId == null || symbol == null || symbol.isBlank() || key == null || key.isBlank()
            || clientOrderId == null || clientOrderId.isBlank() || instanceId == null || finalizeExecution == null) {
            throw new IllegalArgumentException("缺少精确的策略标识，无法核验 LIVE 发送票据");
        }
        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            int updated = mapper.markNotExecuted(credentialId, normalizedSymbol, key, clientOrderId, instanceId);
            if (updated == 1) {
                if (mapper.release(credentialId, normalizedSymbol, key) != 1) {
                    throw new IllegalStateException("已核实 OPEN 未执行，但 LIVE 账户交易对锁未能安全释放");
                }
            } else {
                String ticketStatus = mapper.notExecutedTicketStatus(credentialId, normalizedSymbol, key, clientOrderId, instanceId);
                if ("VERIFIED_NOT_EXECUTED".equalsIgnoreCase(ticketStatus)) {
                    java.util.Map<String, Object> active = mapper.activeDispatch(credentialId, normalizedSymbol);
                    if (active != null && key.equals(stringValue(active, "dispatchKey"))
                        && mapper.release(credentialId, normalizedSymbol, key) != 1) {
                        throw new IllegalStateException("已终结 OPEN 未执行票据，但残留账户交易对锁无法释放");
                    }
                } else {
                    if (ticketStatus != null) {
                        return false;
                    }
                    java.util.Map<String, Object> active = mapper.activeDispatch(credentialId, normalizedSymbol);
                    if (active != null && key.equals(stringValue(active, "dispatchKey"))) {
                        return false;
                    }
                }
            }
            if (!finalizeExecution.getAsBoolean()) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        }));
    }

    /**
     * Called ONLY after querying the exact Binance order and durably reconciling
     * the local order ledger. Legacy (pre-migration) orders have no ticket.
     * Never release on an absent/ambiguous exchange response.
     *
     * @return true when an unresolved ticket was recovered and its guard released
     */
    public boolean recoveredFromExchange(Long credentialId, String symbol, String key, String clientOrderId, Long exchangeOrderId) {
        if (credentialId == null || symbol == null || symbol.isBlank() || key == null || key.isBlank() || clientOrderId == null || clientOrderId.isBlank() || exchangeOrderId == null || exchangeOrderId <= 0) {
            throw new IllegalArgumentException("缺少精确的交易所标识，无法对账 LIVE 票据");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (mapper.recoverVerified(credentialId, symbol.toUpperCase(Locale.ROOT), key, clientOrderId, exchangeOrderId) != 1) {
                return false;
            }
            if (mapper.release(credentialId, symbol.toUpperCase(Locale.ROOT), key) != 1) {
                throw new IllegalStateException("交易所事实已恢复，但持久化发送保护条件不匹配");
            }
            return true;
        }));
    }

    /**
     * Find the ONLY currently held protection ticket, if its persisted operation
     * and client algo ID match the exact exchange object being reconciled.
     */
    public String activeProtectionTicket(Long credentialId, String symbol, String clientAlgoId, String operation) {
        if (credentialId == null || symbol == null || symbol.isBlank() || clientAlgoId == null || clientAlgoId.isBlank() || !java.util.Set.of("PROTECT_CREATE", "PROTECT_CANCEL").contains(operation)) {
            throw new IllegalArgumentException("缺少精确标识，无法检查保护单发送状态");
        }
        return mapper.activeProtectionTicket(credentialId, symbol.toUpperCase(Locale.ROOT), clientAlgoId, operation);
    }

    /**
     * 返回当前账户交易对派发槽的持有事实；本地票据缺失时也返回占用记录，供启动门禁 fail-closed。
     */
    public ActiveDispatch activeDispatch(Long credentialId, String symbol) {
        if (credentialId == null || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("缺少账户和交易对，无法检查发送槽位");
        }
        return toActiveDispatch(mapper.activeDispatch(credentialId, symbol.toUpperCase(Locale.ROOT)));
    }

    /**
     * 列出账户或指定交易对当前持有的未决保护撤单票据，供连接恢复和事件恢复使用。
     */
    public java.util.List<ActiveDispatch> activeProtectionCancels(Long credentialId, String symbol) {
        if (credentialId == null || credentialId <= 0) {
            throw new IllegalArgumentException("缺少账户标识，无法检查保护单发送记录");
        }
        return mapper.activeProtectionCancels(credentialId, symbol == null ? null : symbol.toUpperCase(Locale.ROOT)).stream()
            .map(LiveOrderDispatcher::toActiveDispatch).filter(Objects::nonNull).toList();
    }

    /** List unresolved protection create and cancel tickets for connection recovery. */
    public java.util.List<ActiveDispatch> activeProtectionDispatches(Long credentialId, String symbol) {
        if (credentialId == null || credentialId <= 0) {
            throw new IllegalArgumentException("缺少账户标识，无法检查保护单发送记录");
        }
        return mapper.activeProtectionDispatches(credentialId, symbol == null ? null : symbol.toUpperCase(Locale.ROOT)).stream()
            .map(LiveOrderDispatcher::toActiveDispatch).filter(Objects::nonNull).toList();
    }

    private static ActiveDispatch toActiveDispatch(java.util.Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        return new ActiveDispatch(value(row, "credentialId"), stringValue(row, "symbol"), stringValue(row, "dispatchKey"),
            stringValue(row, "operationType"), stringValue(row, "clientOrderId"), stringValue(row, "dispatchStatus"), value(row, "exchangeOrderId"));
    }

    private static Long value(java.util.Map<String, Object> row, String name) {
        Object value = row.entrySet().stream().filter(entry -> name.equalsIgnoreCase(entry.getKey())).map(java.util.Map.Entry::getValue).findFirst().orElse(null);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String stringValue(java.util.Map<String, Object> row, String name) {
        return row.entrySet().stream().filter(entry -> name.equalsIgnoreCase(entry.getKey())).map(java.util.Map.Entry::getValue)
            .filter(Objects::nonNull).map(Object::toString).findFirst().orElse(null);
    }

    /**
     * Recovery may only be called AFTER the exact algo order ID, client ID and
     * locally persisted order status have been independently verified.
     */
    public boolean recoverVerifiedProtection(Long credentialId, String symbol, String clientAlgoId, String operation, Long algoOrderId) {
        if (algoOrderId == null || algoOrderId <= 0) {
            throw new IllegalArgumentException("缺少已验证的算法订单 ID");
        }
        String key = activeProtectionTicket(credentialId, symbol, clientAlgoId, operation);
        return key != null && recoveredFromExchange(credentialId, symbol, key, clientAlgoId, algoOrderId);
    }

    /**
     * Keep slot occupied after a remote timeout, process crash or failed local persist.
     */
    public void unknown(Ticket ticket) {
        transaction.executeWithoutResult(status -> {
            if (mapper.unknown(ticket.credentialId(), ticket.key()) != 1) {
                String current = mapper.ticketStatus(ticket.credentialId(), ticket.key());
                // A recovery worker may persist the exact exchange fact and release
                // this ticket while the original request thread is still unwinding.
                // Do not turn that completed race into a false UNKNOWN failure.
                if (!java.util.Set.of("UNKNOWN", "ACKED", "RECOVERED", "CONFIG_APPLIED", "FAILED_NOT_SENT").contains(current)) {
                    throw new IllegalStateException("LIVE 发送结果未知且票据状态异常，禁止重新发送");
                }
            }
        });
        ApplicationEventPublisher publisher = eventPublisher;
        if (publisher != null) {
            publisher.publishEvent(new DispatchRecoveryRequestedEvent(ticket.credentialId(), ticket.symbol()));
        }
    }

    /**
     * 用于串行化同账户、同交易对派发的本地队列票据。
     */
    public record Ticket(Long credentialId, String symbol, String key) {
    }

    /** 当前账户交易对门禁占用的不可变票据事实。 */
    public record ActiveDispatch(Long credentialId, String symbol, String dispatchKey, String operationType, String clientOrderId, String dispatchStatus,
                                 Long exchangeOrderId) {
    }

    /**
     * 派发槽或策略租约校验在任何交易所请求发送前失败。
     */
    public static final class DispatchBlockedException extends IllegalStateException {
        public DispatchBlockedException(String message) {
            super(message);
        }
    }
}
