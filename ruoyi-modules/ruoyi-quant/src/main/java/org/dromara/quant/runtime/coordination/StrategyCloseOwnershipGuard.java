package org.dromara.quant.runtime.coordination;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.runtime.coordination.mapper.StrategyCloseOwnershipMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Conservative LIVE close ownership gate.
 *
 * <p>The exchange exposes an aggregate account position. Until per-strategy fill
 * attribution is authoritative, a runtime may only reduce the quantity of the
 * strategy instance that has already persisted the exact pending exit intent.
 * Untrusted, missing or shared facts fail closed.
 */
@Service
@RequiredArgsConstructor
public class StrategyCloseOwnershipGuard {
    private final StrategyCloseOwnershipMapper mapper;

    /**
     * 确认平仓请求归属于持有该策略持仓周期的实例。
     */
    public void requireOwnedClose(Long credentialId, Long strategyId, Long instanceId, String symbol, String orderSide, BigDecimal quantity, String idempotencyKey) {
        if (credentialId == null || strategyId == null || instanceId == null || symbol == null || symbol.isBlank() || quantity == null || quantity.signum() <= 0 || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalStateException("LIVE 策略平仓归属参数不可信");
        }
        Map<String, Object> state = mapper.pendingOwnedExit(instanceId, credentialId, strategyId, symbol.toUpperCase(Locale.ROOT));
        if (state == null || !"EXIT_PENDING".equals(state.get("status")) || !idempotencyKey.equals(state.get("pending_idempotency_key")) || state.get("pending_action") == null) {
            throw new IllegalStateException("LIVE 策略未持有匹配的待确认平仓意图");
        }
        Object unsafe = state.get("unsafe_recovery");
        if (unsafe == null || Boolean.TRUE.equals(unsafe) || unsafe instanceof Number n && n.intValue() != 0) {
            throw new IllegalStateException("LIVE 策略仓位恢复状态不可信，拒绝平仓");
        }
        String expectedDirection = "SELL".equalsIgnoreCase(orderSide) ? "LONG" : "BUY".equalsIgnoreCase(orderSide) ? "SHORT" : "";
        if (!expectedDirection.equalsIgnoreCase(Objects.toString(state.get("direction"), ""))) {
            throw new IllegalStateException("LIVE 策略平仓方向与持仓归属不一致");
        }
        Object allocated = state.get("position_qty");
        if (!(allocated instanceof BigDecimal ownedQty) || ownedQty.signum() <= 0 || quantity.compareTo(ownedQty) > 0) {
            throw new IllegalStateException("LIVE 平仓数量超出策略实例已确认持仓额度");
        }
    }
}
