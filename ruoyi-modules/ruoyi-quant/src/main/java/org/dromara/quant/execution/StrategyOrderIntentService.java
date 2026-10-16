package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Durable attribution of a proven exchange-accepted strategy order.
 * Never infers the position role from BUY/SELL or from mutable order state.
 * Only unambiguous LONG/SHORT orders may be recorded. BOTH, unknown mode,
 * missing identifiers, and conflicting replays fail closed.
 * <p>
 * This operation must run after Binance acceptance plus local order-ledger
 * persistence. Schema V09 must be migrated before enabling this writer.
 */
@Service
public class StrategyOrderIntentService {
    private final StrategyOrderIntentMapper mapper;

    public StrategyOrderIntentService(StrategyOrderIntentMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
    }

    private static boolean equalsId(Object actual, long expected) {
        return actual instanceof Number n && n.longValue() == expected;
    }

    /**
     * 持久化策略订单意图和幂等键，供崩溃恢复精确对账。
     */
    public void record(long credentialId, long strategyId, long instanceId, String operation, ExchangeGateway.OrderRequest request, ExchangeGateway.OrderResult accepted) {
        if (credentialId <= 0 || strategyId <= 0 || instanceId <= 0 || request == null || accepted == null || accepted.orderId() == null || accepted.orderId() <= 0 || request.symbol() == null || request.symbol().isBlank() || request.clientOrderId() == null || request.clientOrderId().isBlank() || accepted.clientOrderId() == null || !request.clientOrderId().equals(accepted.clientOrderId()) || request.positionSide() == null || !java.util.Set.of("LONG", "SHORT").contains(request.positionSide().toUpperCase(Locale.ROOT)) || !java.util.Set.of("OPEN", "CLOSE").contains(operation) || request.side() == null || !java.util.Set.of("BUY", "SELL").contains(request.side().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("Cannot establish unambiguous immutable strategy order intent");
        }
        String role = operation.toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        String positionSide = request.positionSide().toUpperCase(Locale.ROOT);
        String expected = ((positionSide.equals("LONG")) == role.equals("OPEN")) ? "BUY" : "SELL";
        if (!side.equals(expected) || role.equals("CLOSE") && !request.reduceOnly() || role.equals("OPEN") && request.reduceOnly()) {
            throw new IllegalStateException("Order role, position side and execution direction conflict");
        }
        String symbol = request.symbol().toUpperCase(Locale.ROOT);
        try {
            if (mapper.insert(credentialId, symbol, accepted.orderId(), strategyId, instanceId, positionSide, role, side, request.clientOrderId()) != 1) {
                throw new IllegalStateException("Strategy order intent was not persisted");
            }
        } catch (DuplicateKeyException replay) {
            Map<String, Object> prior = mapper.find(credentialId, symbol, accepted.orderId());
            if (prior == null || !equalsId(prior.get("strategy_id"), strategyId) || !equalsId(prior.get("strategy_instance_id"), instanceId) || !role.equals(prior.get("fill_role"
            )) || !positionSide.equals(prior.get("position_side")) || !side.equals(prior.get("exchange_side")) || !request.clientOrderId().equals(prior.get("client_order_id"))) {
                throw new IllegalStateException("Conflicting immutable strategy order identity", replay);
            }
        }
    }
}
