package org.dromara.quant.execution;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed account-level completeness gate.
 *
 * Inputs MUST be from an authenticated, temporally consistent Binance snapshot
 * and a database snapshot after every exchange fill is attributed. A numeric
 * match alone cannot prove that all fills are accounted for.
 */
public final class StrategyPositionReconciliation {
    private StrategyPositionReconciliation() {}

    public record Quantity(BigDecimal longQuantity, BigDecimal shortQuantity) {
        public Quantity {
            Objects.requireNonNull(longQuantity);
            Objects.requireNonNull(shortQuantity);
            if (longQuantity.signum()<0 || shortQuantity.signum()<0) {
                throw new IllegalArgumentException("Negative unsigned hedge inventory");
            }
        }
    }

    public static void requireMatching(Quantity exchange, Quantity allocated,
                                       boolean completeFillHistory,
                                       boolean noUnattributedManualFills,
                                       boolean sameSnapshotWatermark) {
        if (exchange==null || allocated==null || !completeFillHistory
            || !noUnattributedManualFills || !sameSnapshotWatermark
            || exchange.longQuantity().compareTo(allocated.longQuantity())!=0
            || exchange.shortQuantity().compareTo(allocated.shortQuantity())!=0) {
            throw new IllegalStateException(
                "Strategy position cannot be proven equal to Binance account inventory");
        }
    }
}
