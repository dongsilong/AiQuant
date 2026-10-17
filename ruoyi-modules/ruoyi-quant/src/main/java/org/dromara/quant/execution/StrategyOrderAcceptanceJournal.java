package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Binance-accepted order and its immutable strategy provenance commit as
 * a single local transaction. Remote HTTP MUST happen before this method.
 * A database failure rolls back both rows and leaves the caller's durable
 * dispatch ticket UNKNOWN, never "not sent".
 */
@Service
public class StrategyOrderAcceptanceJournal {
    private final OrderLedgerService ledger;
    private final StrategyOrderIntentService intents;

    public StrategyOrderAcceptanceJournal(OrderLedgerService ledger, StrategyOrderIntentService intents) {
        this.ledger = java.util.Objects.requireNonNull(ledger);
        this.intents = java.util.Objects.requireNonNull(intents);
    }

    /**
     * 在提交订单前写入可恢复的接受意图，并持久化本地订单状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuantOrder persist(Long credentialId, Long strategyId, Long instanceId, String runMode, String operation, ExchangeGateway.OrderRequest request,
                              ExchangeGateway.OrderResult result) {
        if (credentialId == null || credentialId <= 0 || request == null || result == null) {
            throw new IllegalArgumentException("Missing accepted order identity");
        }
        QuantOrder order = ledger.upsert(credentialId, strategyId, instanceId, request, result);
        if (order == null || order.getId() == null) {
            throw new IllegalStateException("Exchange order ledger was not persisted");
        }
        if (strategyId != null && instanceId != null && "LIVE".equalsIgnoreCase(runMode) && java.util.Set.of("LONG", "SHORT").contains(request.positionSide() == null ? "" :
            request.positionSide().toUpperCase(java.util.Locale.ROOT))) {
            // This does not invent attribution for BOTH/one-way mode.
            // A failure rolls the ledger back and keeps the dispatch guard held.
            intents.record(credentialId, strategyId, instanceId, operation, request, result);
        }
        return order;
    }
}
