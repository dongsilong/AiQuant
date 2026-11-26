package org.dromara.quant.execution;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.runtime.DualHedgePositionCycleKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persist TESTNET dual-hedge leg ownership before the first exchange side effect.
 * LIVE continues to persist the same fact atomically with its dispatch ticket.
 */
@Service
@RequiredArgsConstructor
public class StrategyPreDispatchIntentService {
    private static final long NON_LIVE_LEASE_EPOCH = 0L;

    private final LiveOrderDispatchMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public void recordTestnetOpenIntent(Long credentialId, Long strategyId, Long instanceId, String symbol,
                                        String idempotencyKey, String clientOrderId, String positionSide,
                                        String exchangeSide, String positionCycleKey) {
        if (credentialId == null || credentialId <= 0 || strategyId == null || strategyId <= 0
            || instanceId == null || instanceId <= 0 || symbol == null || symbol.isBlank()
            || clientOrderId == null || clientOrderId.isBlank()
            || !DualHedgePositionCycleKey.matchesOpenIdempotencyKey(idempotencyKey, positionSide, positionCycleKey)
            || !("LONG".equals(positionSide) && "BUY".equals(exchangeSide)
                || "SHORT".equals(positionSide) && "SELL".equals(exchangeSide))) {
            throw new IllegalArgumentException("TESTNET 双开预派发订单身份或腿方向不完整");
        }
        if (mapper.insertPreDispatchIntentWithCycle(credentialId, symbol.toUpperCase(java.util.Locale.ROOT),
            idempotencyKey, clientOrderId, strategyId, instanceId, "OPEN", positionSide, exchangeSide,
            NON_LIVE_LEASE_EPOCH, positionCycleKey) != 1) {
            throw new IllegalStateException("TESTNET 双开预派发来源记录未能持久化，拒绝发送订单");
        }
    }
}
