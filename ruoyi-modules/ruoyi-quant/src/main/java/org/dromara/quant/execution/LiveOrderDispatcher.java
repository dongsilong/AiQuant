package org.dromara.quant.execution;

import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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

    public record Ticket(Long credentialId, String symbol, String key) {}

    public LiveOrderDispatcher(LiveOrderDispatchMapper mapper, PlatformTransactionManager manager) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setTimeout(5);
    }

    /**
     * Both ticket creation and exclusive guard claim commit atomically.
     * The durable slot is never auto-freed by process death, lease expiry or time.
     */
    public Ticket begin(Long credentialId, String symbol, String key, String operation,
                        String clientOrderId, Long instanceId, Long epoch) {
        if ("CONFIG_SETTINGS".equals(operation)) {
            throw new IllegalArgumentException("配置操作必须通过 beginConfiguration 核实全局参数契约");
        }
        return reserve(credentialId, symbol, key, operation, clientOrderId,
            instanceId, epoch, null, null);
    }

    public Ticket beginConfiguration(Long credentialId, String symbol, String key,
                                     String marginMode, int leverage) {
        if (!java.util.Set.of("ISOLATED", "CROSSED").contains(marginMode)
            || leverage < 1 || leverage > 125) {
            throw new IllegalArgumentException("LIVE Binance 配置范围或保证金模式无效");
        }
        return reserve(credentialId, symbol, key, "CONFIG_SETTINGS",
            "SETTINGS:" + marginMode + ":" + leverage, null, null, marginMode, leverage);
    }

    private Ticket reserve(Long credentialId, String symbol, String key, String operation,
                           String clientOrderId, Long instanceId, Long epoch,
                           String marginMode, Integer leverage) {
        if (credentialId == null || credentialId <= 0 || symbol == null || symbol.isBlank()
            || key == null || key.isBlank() || operation == null || operation.isBlank()
            || clientOrderId == null || clientOrderId.isBlank()
            || instanceId != null && (epoch == null || epoch <= 0)) {
            throw new IllegalArgumentException("LIVE dispatch 缺少可靠的订单或租约标识");
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        return transaction.execute(status -> {
            mapper.ensureGuard(credentialId, normalized);
            int claimed = instanceId == null
                ? mapper.claim(credentialId, normalized, key)
                : mapper.claimWithLease(credentialId, normalized, key, instanceId, epoch);
            if (claimed != 1) {
                throw new IllegalStateException("同账户交易对存在未完成交易所操作或策略租约已过期/转移，拒绝发送");
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
            return new Ticket(credentialId, normalized, key);
        });
    }

    /** Acknowledge only after the exchange acceptance AND local order ledger are durable. */
    public void acknowledged(Ticket ticket, Long exchangeOrderId) {
        if (ticket == null || exchangeOrderId == null || exchangeOrderId <= 0) {
            throw new IllegalStateException("LIVE 订单没有可信交易所订单 ID，必须保持发送票据占用");
        }
        transaction.executeWithoutResult(status -> {
            if (mapper.acknowledge(ticket.credentialId(), ticket.key(), exchangeOrderId) != 1
                || mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
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
        if (ticket == null) throw new IllegalArgumentException("Missing configuration dispatch ticket");
        transaction.executeWithoutResult(status -> {
            if (mapper.acknowledgeConfiguration(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1
                || mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("账户配置未能可靠确认，保持该交易对调度阻断");
            }
        });
    }

    /** Reserved for a provably NOT-SENT result. All timeouts are UNKNOWN instead. */
    public void notSent(Ticket ticket) {
        transaction.executeWithoutResult(status -> {
            if (mapper.notSent(ticket.credentialId(), ticket.key()) != 1
                || mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("LIVE 未发送票据无法安全解除调度占用");
            }
        });
    }

    /**
     * Called ONLY after querying the exact Binance order and durably reconciling
     * the local order ledger. Legacy (pre-migration) orders have no ticket.
     * Never release on an absent/ambiguous exchange response.
     *
     * @return true when an unresolved ticket was recovered and its guard released
     */
    public boolean recoveredFromExchange(Long credentialId, String symbol, String key,
                                         String clientOrderId, Long exchangeOrderId) {
        if (credentialId == null || symbol == null || symbol.isBlank() || key == null || key.isBlank()
            || clientOrderId == null || clientOrderId.isBlank() || exchangeOrderId == null
            || exchangeOrderId <= 0) {
            throw new IllegalArgumentException("Cannot reconcile a LIVE ticket without exact exchange identity");
        }
        return Boolean.TRUE.equals(transaction.execute(status -> {
            if (mapper.recoverVerified(credentialId, symbol.toUpperCase(Locale.ROOT),
                    key, clientOrderId, exchangeOrderId) != 1) {
                return false;
            }
            if (mapper.release(credentialId, symbol.toUpperCase(Locale.ROOT), key) != 1) {
                throw new IllegalStateException("Recovered exchange fact but durable dispatch guard mismatch");
            }
            return true;
        }));
    }

    /**
     * Find the ONLY currently held protection ticket, if its persisted operation
     * and client algo ID match the exact exchange object being reconciled.
     */
    public String activeProtectionTicket(Long credentialId, String symbol, String clientAlgoId,
                                         String operation) {
        if (credentialId == null || symbol == null || symbol.isBlank()
            || clientAlgoId == null || clientAlgoId.isBlank()
            || !java.util.Set.of("PROTECT_CREATE", "PROTECT_CANCEL").contains(operation)) {
            throw new IllegalArgumentException("Cannot inspect protection dispatch without exact identity");
        }
        return mapper.activeProtectionTicket(credentialId, symbol.toUpperCase(Locale.ROOT),
            clientAlgoId, operation);
    }

    /**
     * Recovery may only be called AFTER the exact algo order ID, client ID and
     * locally persisted order status have been independently verified.
     */
    public boolean recoverVerifiedProtection(Long credentialId, String symbol, String clientAlgoId,
                                             String operation, Long algoOrderId) {
        if (algoOrderId == null || algoOrderId <= 0) {
            throw new IllegalArgumentException("Missing verified algo order ID");
        }
        String key = activeProtectionTicket(credentialId, symbol, clientAlgoId, operation);
        return key != null && recoveredFromExchange(credentialId, symbol, key, clientAlgoId, algoOrderId);
    }

    /** Keep slot occupied after a remote timeout, process crash or failed local persist. */
    public void unknown(Ticket ticket) {
        transaction.executeWithoutResult(status -> {
            if (mapper.unknown(ticket.credentialId(), ticket.key()) != 1) {
                throw new IllegalStateException("LIVE 发送结果未知且票据状态异常，禁止重新发送");
            }
        });
    }
}
