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
        if (credentialId == null || credentialId <= 0 || symbol == null || symbol.isBlank()
            || key == null || key.isBlank() || operation == null || operation.isBlank()
            || clientOrderId == null || clientOrderId.isBlank()
            || instanceId != null && (epoch == null || epoch <= 0)) {
            throw new IllegalArgumentException("LIVE dispatch 缺少可靠的订单或租约标识");
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        return transaction.execute(status -> {
            mapper.ensureGuard(credentialId, normalized);
            if (mapper.claim(credentialId, normalized, key) != 1) {
                throw new IllegalStateException("同账户交易对存在未完成或未知的交易所操作，拒绝并发发送");
            }
            if (mapper.insertTicket(credentialId, normalized, key, operation, clientOrderId, instanceId, epoch) != 1) {
                throw new IllegalStateException("LIVE dispatch 发送票据落库失败");
            }
            return new Ticket(credentialId, normalized, key);
        });
    }

    /** Acknowledge only after the exchange acceptance AND local order ledger are durable. */
    public void acknowledged(Ticket ticket, Long exchangeOrderId) {
        transaction.executeWithoutResult(status -> {
            if (mapper.acknowledge(ticket.credentialId(), ticket.key(), exchangeOrderId) != 1
                || mapper.release(ticket.credentialId(), ticket.symbol(), ticket.key()) != 1) {
                throw new IllegalStateException("LIVE 订单已发出，但无法安全释放调度锁，等待人工核对");
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

    /** Keep slot occupied after a remote timeout, process crash or failed local persist. */
    public void unknown(Ticket ticket) {
        transaction.executeWithoutResult(status -> {
            if (mapper.unknown(ticket.credentialId(), ticket.key()) != 1) {
                throw new IllegalStateException("LIVE 发送结果未知且票据状态异常，禁止重新发送");
            }
        });
    }
}
