package org.dromara.quant.execution;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

/**
 * Durable attribution of a proven exchange-accepted strategy order.
 * Never infers position ownership from BUY/SELL or mutable order state.
 * Exchange BOTH is accepted only when the exact immutable pre-dispatch record
 * supplies its logical LONG/SHORT side and persisted position-cycle key.
 * Missing identifiers, missing cycle identity, and conflicting replays fail closed.
 * <p>
 * This operation must run after Binance acceptance plus local order-ledger
 * persistence. Schema V09 and the position-cycle migration are prerequisites.
 */
@Service
@RequiredArgsConstructor
public class StrategyOrderIntentService {
    @NonNull private final StrategyOrderIntentMapper mapper;



    private static boolean equalsId(Object actual, long expected) {
        return actual instanceof Number n && n.longValue() == expected;
    }

    /**
     * 持久化策略订单意图和幂等键，供崩溃恢复精确对账。
     */
    public void record(long credentialId, long strategyId, long instanceId, String operation, ExchangeGateway.OrderRequest request, ExchangeGateway.OrderResult accepted) {
        record(credentialId, strategyId, instanceId, operation, request, accepted, null);
    }

    /**
     * LIVE 策略订单必须与发送前已提交的周期归属完全一致。
     */
    public void record(long credentialId, long strategyId, long instanceId, String operation, ExchangeGateway.OrderRequest request,
                       ExchangeGateway.OrderResult accepted, String expectedPositionCycleKey) {
        if (credentialId <= 0 || strategyId <= 0 || instanceId <= 0 || request == null || accepted == null || accepted.orderId() == null || accepted.orderId() <= 0
            || request.symbol() == null || request.symbol().isBlank() || request.clientOrderId() == null || request.clientOrderId().isBlank()
            || accepted.clientOrderId() == null || !request.clientOrderId().equals(accepted.clientOrderId()) || request.positionSide() == null
            || !java.util.Set.of("LONG", "SHORT", "BOTH").contains(request.positionSide().toUpperCase(Locale.ROOT))
            || operation == null || !java.util.Set.of("OPEN", "CLOSE").contains(operation.toUpperCase(Locale.ROOT))
            || request.side() == null || !java.util.Set.of("BUY", "SELL").contains(request.side().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("Cannot establish unambiguous immutable strategy order intent");
        }
        String role = operation.toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        String requestedPositionSide = request.positionSide().toUpperCase(Locale.ROOT);
        String symbol = request.symbol().toUpperCase(Locale.ROOT);
        Map<String, Object> pre = mapper.pendingByClient(credentialId, symbol, request.clientOrderId());
        if (pre == null || !equalsId(pre.get("strategy_id"), strategyId) || !equalsId(pre.get("strategy_instance_id"), instanceId)
            || !symbol.equals(pre.get("symbol")) || !role.equals(pre.get("operation_type"))
            || !side.equals(pre.get("exchange_side")) || !request.clientOrderId().equals(pre.get("client_order_id"))
            || !(pre.get("position_cycle_key") instanceof String cycleKey) || cycleKey.isBlank()
            || expectedPositionCycleKey != null && !expectedPositionCycleKey.equals(cycleKey)
            || !(pre.get("position_side") instanceof String positionSide)
            || !java.util.Set.of("LONG", "SHORT").contains(positionSide)) {
            throw new IllegalStateException("Missing or conflicting immutable pre-dispatch strategy cycle identity");
        }
        if (!"BOTH".equals(requestedPositionSide) && !requestedPositionSide.equals(positionSide)) {
            throw new IllegalStateException("Exchange order position side conflicts with immutable strategy intent");
        }
        String expected = ((positionSide.equals("LONG")) == role.equals("OPEN")) ? "BUY" : "SELL";
        if (!side.equals(expected) || role.equals("CLOSE") && !request.reduceOnly() || role.equals("OPEN") && request.reduceOnly()) {
            throw new IllegalStateException("Order role, position side and execution direction conflict");
        }
        persistIntent(credentialId, symbol, accepted.orderId(), strategyId, instanceId, positionSide, role, cycleKey, side,
            request.clientOrderId());
    }

    /**
     * TESTNET 双开订单在交易所返回确定订单身份后，保存本轮每条腿的不可变来源。
     * LIVE 仍必须使用发送前意图，不能通过此入口绕过派发门禁。
     */
    public void recordAccepted(long credentialId, long strategyId, long instanceId, String operation, ExchangeGateway.OrderRequest request,
                               ExchangeGateway.OrderResult accepted, String positionCycleKey) {
        if (credentialId <= 0 || strategyId <= 0 || instanceId <= 0 || request == null || accepted == null || accepted.orderId() == null || accepted.orderId() <= 0
            || request.symbol() == null || request.symbol().isBlank() || request.clientOrderId() == null || request.clientOrderId().isBlank()
            || accepted.clientOrderId() == null || !request.clientOrderId().equals(accepted.clientOrderId())
            || positionCycleKey == null || positionCycleKey.isBlank() || request.positionSide() == null
            || operation == null || !java.util.Set.of("OPEN", "CLOSE").contains(operation.toUpperCase(Locale.ROOT))
            || request.side() == null || !java.util.Set.of("BUY", "SELL").contains(request.side().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("缺少 TESTNET 双开订单的确定身份或持仓周期键");
        }
        String role = operation.toUpperCase(Locale.ROOT);
        String side = request.side().toUpperCase(Locale.ROOT);
        String positionSide = request.positionSide().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("LONG", "SHORT").contains(positionSide) || !positionCycleKey.endsWith(":" + positionSide)) {
            throw new IllegalStateException("TESTNET 双开订单的持仓方向与周期键不匹配");
        }
        String expected = (positionSide.equals("LONG") == role.equals("OPEN")) ? "BUY" : "SELL";
        if (!expected.equals(side) || role.equals("CLOSE") && !request.reduceOnly() || role.equals("OPEN") && request.reduceOnly()) {
            throw new IllegalStateException("TESTNET 双开订单开平仓方向与持仓方向冲突");
        }
        persistIntent(credentialId, request.symbol().toUpperCase(Locale.ROOT), accepted.orderId(), strategyId, instanceId,
            positionSide, role, positionCycleKey, side, request.clientOrderId());
    }

    private void persistIntent(long credentialId, String symbol, long orderId, long strategyId, long instanceId, String positionSide,
                               String role, String cycleKey, String side, String clientOrderId) {
        try {
            if (mapper.insert(credentialId, symbol, orderId, strategyId, instanceId, positionSide, role, cycleKey, side, clientOrderId) != 1) {
                throw new IllegalStateException("Strategy order intent was not persisted");
            }
        } catch (DuplicateKeyException replay) {
            Map<String, Object> prior = mapper.find(credentialId, symbol, orderId);
            if (prior == null || !equalsId(prior.get("strategy_id"), strategyId)
                || !equalsId(prior.get("strategy_instance_id"), instanceId) || !role.equals(prior.get("fill_role"))
                || !positionSide.equals(prior.get("position_side")) || !cycleKey.equals(prior.get("position_cycle_key"))
                || !side.equals(prior.get("exchange_side")) || !clientOrderId.equals(prior.get("client_order_id"))) {
                throw new IllegalStateException("Conflicting immutable strategy order identity", replay);
            }
        }
    }

    /**
     * Persist a protection child only after ProtectionOrderJournalService has verified
     * the exact algo-to-child relationship and frozen cycle owner.
     */
    public void recordVerifiedProtectionChild(long credentialId, long strategyId, long instanceId,
                                              String symbol, String positionCycleKey, String positionSide,
                                              String exchangeSide, long orderId, String clientOrderId) {
        String normalizedSymbol = symbol == null ? null : symbol.toUpperCase(Locale.ROOT);
        String normalizedPositionSide = positionSide == null ? null : positionSide.toUpperCase(Locale.ROOT);
        String normalizedExchangeSide = exchangeSide == null ? null : exchangeSide.toUpperCase(Locale.ROOT);
        String expectedSide = "LONG".equals(normalizedPositionSide) ? "SELL"
            : "SHORT".equals(normalizedPositionSide) ? "BUY" : null;
        if (credentialId <= 0 || strategyId <= 0 || instanceId <= 0 || normalizedSymbol == null || normalizedSymbol.isBlank()
            || positionCycleKey == null || positionCycleKey.isBlank()
            || expectedSide == null || !expectedSide.equals(normalizedExchangeSide)
            || orderId <= 0 || clientOrderId == null || clientOrderId.isBlank()) {
            throw new IllegalStateException("Cannot persist a protection child without an exact verified strategy-cycle identity");
        }
        try {
            if (mapper.insert(credentialId, normalizedSymbol, orderId, strategyId, instanceId,
                normalizedPositionSide, "CLOSE", positionCycleKey, normalizedExchangeSide, clientOrderId) != 1) {
                throw new IllegalStateException("Verified protection child close intent was not persisted");
            }
        } catch (DuplicateKeyException replay) {
            Map<String, Object> prior = mapper.find(credentialId, normalizedSymbol, orderId);
            if (prior == null || !equalsId(prior.get("strategy_id"), strategyId)
                || !equalsId(prior.get("strategy_instance_id"), instanceId)
                || !"CLOSE".equals(prior.get("fill_role"))
                || !normalizedPositionSide.equals(prior.get("position_side"))
                || !positionCycleKey.equals(prior.get("position_cycle_key"))
                || !normalizedExchangeSide.equals(prior.get("exchange_side"))
                || !clientOrderId.equals(prior.get("client_order_id"))) {
                throw new IllegalStateException("Conflicting immutable protection child order identity", replay);
            }
        }
    }

}
