package org.dromara.quant.execution;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.mapper.StrategyFillAllocationMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Exactly-once, immutable attribution of REAL exchange fill facts.
 * Never manufactures a strategy share from an aggregated account position.
 *
 * <p>Call inside the reconciliation short transaction after precise order mapping.
 * A duplicate trade may only be replayed if every allocation fact agrees.
 */
@Service
@RequiredArgsConstructor
public class StrategyFillAllocationService {
    private final StrategyFillAllocationMapper mapper;

    public void allocate(ExchangeGateway.TradeSnapshot fill, QuantOrder order, Long credentialId) {
        if (fill == null || order == null || credentialId == null
            || fill.tradeId() == null || fill.tradeId() <= 0
            || fill.orderId() == null || fill.orderId() <= 0
            || fill.quantity() == null || fill.quantity().signum() <= 0
            || fill.price() == null || fill.price().signum() <= 0
            || order.getStrategyId() == null || order.getStrategyInstanceId() == null
            || !credentialId.equals(order.getCredentialId())
            || !fill.orderId().equals(order.getExchangeOrderId())
            || fill.symbol() == null || !fill.symbol().equalsIgnoreCase(order.getSymbol())) {
            throw new IllegalStateException("缺少交易所成交或可证明的策略订单归属，不得分配成交");
        }
        String symbol = fill.symbol().toUpperCase(Locale.ROOT);
        // A missing commission/PnL is UNKNOWN, not zero: silent coercion
        // corrupts the immutable per-strategy financial allocation.
        if (fill.commission() == null || fill.realizedPnl() == null
            || !java.util.Set.of("BUY", "SELL").contains(
                fill.side() == null ? "" : fill.side().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("Binance 成交手续费、盈亏或买卖方向缺失，不得写入不可变分账");
        }
        String feeAsset = fill.commissionAsset() == null ? "" :
            fill.commissionAsset().toUpperCase(Locale.ROOT);
        if (!feeAsset.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalStateException("缺少 Binance 手续费币种，不得将不同资产费用合并");
        }
        BigDecimal fee = fill.commission();
        BigDecimal pnl = fill.realizedPnl();
        LocalDateTime when = fill.time() == null ? null
            : LocalDateTime.ofInstant(fill.time(), ZoneOffset.UTC);
        if (when == null || fill.side() == null || fill.side().isBlank()) {
            throw new IllegalStateException("交易所成交缺少方向或成交时间");
        }
        Map<String,Object> existing = mapper.getAllocation(credentialId, symbol, fill.tradeId());
        if (existing != null) {
            verifyImmutable(existing, fill, order, fee, feeAsset, pnl);
            return;
        }
        try {
            mapper.insertAllocation(credentialId, symbol, fill.tradeId(), fill.orderId(),
                order.getStrategyId(), order.getStrategyInstanceId(), fill.side().toUpperCase(Locale.ROOT),
                fill.quantity(), fill.price(), fee, feeAsset, pnl, when);
        } catch (DuplicateKeyException race) {
            // A second reconciler may have committed the same exchange fill.
            existing = mapper.getAllocation(credentialId, symbol, fill.tradeId());
            if (existing == null) throw race;
            verifyImmutable(existing, fill, order, fee, feeAsset, pnl);
        }
    }

    private static void verifyImmutable(Map<String,Object> row, ExchangeGateway.TradeSnapshot fill,
                                        QuantOrder order, BigDecimal fee, String feeAsset, BigDecimal pnl) {
        if (!sameLong(row.get("strategy_id"), order.getStrategyId())
            || !sameLong(row.get("strategy_instance_id"), order.getStrategyInstanceId())
            || !sameLong(row.get("exchange_order_id"), fill.orderId())
            || !Objects.equals(row.get("side"), fill.side().toUpperCase(Locale.ROOT))
            || !sameTime(row.get("trade_time"), LocalDateTime.ofInstant(fill.time(), ZoneOffset.UTC))
            || !sameDecimal(row.get("allocated_qty"), fill.quantity())
            || !sameDecimal(row.get("allocated_price"), fill.price())
            || !sameDecimal(row.get("allocated_fee"), fee)
            || !feeAsset.equals(row.get("allocated_fee_asset"))
            || !sameDecimal(row.get("realized_pnl"), pnl)) {
            throw new IllegalStateException("已分配的 Binance 成交归属与新事实冲突，禁止覆盖原始持仓份额"
                + " [exchangeTradeId=" + fill.tradeId() + ", symbol=" + fill.symbol()
                + ", exchangeOrderId=" + fill.orderId()
                + ", storedStrategyInstanceId=" + row.get("strategy_instance_id")
                + ", incomingStrategyInstanceId=" + order.getStrategyInstanceId() + "]");
        }
    }

    private static boolean sameLong(Object actual, Long expected) {
        return actual instanceof Number n && Objects.equals(n.longValue(), expected);
    }
    private static boolean sameDecimal(Object actual, BigDecimal expected) {
        return actual instanceof BigDecimal b && b.compareTo(expected) == 0;
    }
    private static boolean sameTime(Object actual, LocalDateTime expected) {
        if (actual instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().equals(expected);
        }
        return actual instanceof LocalDateTime time && time.equals(expected);
    }
}
