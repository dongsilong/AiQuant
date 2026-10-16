package org.dromara.quant.execution;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Deterministic per-strategy, per-position-side fill accounting.
 * Only an exchange-proven OPEN/CLOSE role is accepted: BUY/SELL alone is
 * insufficient for one-way futures, reversals and hedge-mode inventories.
 * <p>
 * The realized PnL is Binance's signed fill fact, never reconstructed from
 * a browser float or an aggregate account position.
 */
public final class StrategyPositionAccounting {
    private StrategyPositionAccounting() {
    }

    /**
     * 按已确认成交推进持仓；不匹配或超出已归属数量的平仓会被拒绝。
     */
    public static State apply(State state, Fill fill) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(fill);
        BigDecimal qty = state.openQuantity();
        BigDecimal cost = state.costBasis();
        BigDecimal nextQty;
        BigDecimal nextCost;
        long cycle = state.cycle();
        if (fill.role() == Role.OPEN) {
            if (qty.signum() == 0) {
                cycle = Math.addExact(cycle, 1);
            }
            nextQty = qty.add(fill.quantity());
            nextCost = cost.add(fill.quantity().multiply(fill.price()));
        } else {
            if (qty.compareTo(fill.quantity()) < 0) {
                throw new IllegalStateException("Unowned or reversing close fill must be quarantined");
            }
            nextQty = qty.subtract(fill.quantity());
            if (nextQty.signum() == 0) {
                nextCost = BigDecimal.ZERO;
            } else {
                nextCost = cost.subtract(cost.multiply(fill.quantity()).divide(qty, MathContext.DECIMAL128));
                if (nextCost.signum() < 0) {
                    throw new IllegalStateException("Cost basis underflow");
                }
            }
        }
        return new State(nextQty, nextCost, state.realizedPnl().add(fill.realizedPnl()), state.commissions().add(fill.commission()), cycle);
    }

    /**
     * 策略持仓的方向，与交易所订单买卖方向区分。
     */
    public enum PositionSide {LONG, SHORT}

    /**
     * 成交在策略持仓周期中的明确作用。
     */
    public enum Role {OPEN, CLOSE}

    /**
     * 单个策略持仓方向的数量、成本和已实现损益状态。
     */
    public record State(BigDecimal openQuantity, BigDecimal costBasis, BigDecimal realizedPnl, BigDecimal commissions, long cycle) {
        public State {
            Objects.requireNonNull(openQuantity);
            Objects.requireNonNull(costBasis);
            Objects.requireNonNull(realizedPnl);
            Objects.requireNonNull(commissions);
            if (openQuantity.signum() < 0 || costBasis.signum() < 0 || cycle < 0) {
                throw new IllegalArgumentException("Invalid strategy inventory");
            }
            if (openQuantity.signum() == 0 && costBasis.signum() != 0) {
                throw new IllegalArgumentException("Closed cycle must have zero cost");
            }
        }

        /**
         * 创建尚未开始任何持仓周期的空账本。
         */
        public static State empty() {
            return new State(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
    }

    /**
     * 带交易所方向和开平角色的已确认成交事实。
     */
    public record Fill(PositionSide positionSide, Role role, String exchangeSide, BigDecimal quantity, BigDecimal price, BigDecimal commission, BigDecimal realizedPnl) {
        public Fill {
            Objects.requireNonNull(positionSide);
            Objects.requireNonNull(role);
            Objects.requireNonNull(exchangeSide);
            Objects.requireNonNull(quantity);
            Objects.requireNonNull(price);
            Objects.requireNonNull(commission);
            Objects.requireNonNull(realizedPnl);
            if (quantity.signum() <= 0 || price.signum() <= 0 || commission.signum() < 0) {
                throw new IllegalArgumentException("Invalid fill financial values");
            }
            String expected = (positionSide == PositionSide.LONG) == (role == Role.OPEN) ? "BUY" : "SELL";
            if (!expected.equals(exchangeSide)) {
                throw new IllegalArgumentException("Side and explicit position role conflict");
            }
        }
    }
}
