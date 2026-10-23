package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Strict verified-fill replay boundary. All exchange queries happen upstream.
 * This never guesses an external/manual fill's strategy, fills a gap, or
 * reassigns trade ownership from aggregate positions.
 * <p>
 * This is deliberately an explicit, bounded replay API, not an automatic
 * always-on Binance poller. Provenance is preflighted for the complete batch
 * before projection begins; a later projection failure can still leave
 * idempotently replayable partial progress.
 */
@Service
@RequiredArgsConstructor
public class StrategyFillReplayService {
    @NonNull private final QuantOrderMapper orders;
    @NonNull private final StrategyPositionProjectionMapper provenance;
    @NonNull private final StrategyPositionProjectionService projector;

    /**
     * Only call after verifying the exchange history window is complete.
     * All fills must have an unambiguous immutable strategy intent before any
     * fill is projected. Projection failures are propagated so callers do not
     * mistake a partial replay for a complete one.
     */
    public ReplayResult replay(long credentialId, String symbol, List<ExchangeGateway.TradeSnapshot> exchangeFills, boolean completeHistoryVerified) {
        if (credentialId <= 0 || symbol == null || symbol.isBlank() || exchangeFills == null || !completeHistoryVerified) {
            throw new IllegalStateException("Complete, authenticated fill history required");
        }
        if (exchangeFills.size() > 1000) {
            throw new IllegalArgumentException("Replay must be bounded and paged");
        }
        List<ExchangeGateway.TradeSnapshot> sorted = new ArrayList<>(exchangeFills);
        if (sorted.stream().anyMatch(t -> t == null || t.time() == null || t.tradeId() == null || t.tradeId() <= 0 || !symbol.equalsIgnoreCase(t.symbol()) || t.orderId() == null || t.commissionAsset() == null || !t.commissionAsset().matches("[A-Z0-9]{2,20}"))) {
            throw new IllegalStateException("Invalid Binance fill in replay window");
        }
        Set<Long> tradeIds = new HashSet<>();
        for (var trade : sorted) {
            if (!tradeIds.add(trade.tradeId())) {
                throw new IllegalStateException("Duplicate Binance trade ID in replay window: " + trade.tradeId());
            }
        }
        sorted.sort(Comparator.comparing(ExchangeGateway.TradeSnapshot::time).thenComparing(ExchangeGateway.TradeSnapshot::tradeId));

        String normalizedSymbol = symbol.toUpperCase(Locale.ROOT);
        List<VerifiedFill> verified = new ArrayList<>(sorted.size());
        for (var trade : sorted) {
            Map<String, Object> origin = provenance.verifiedIntent(credentialId, normalizedSymbol, trade.orderId());
            if (origin == null || !(origin.get("position_side") instanceof String) || !(origin.get("fill_role") instanceof String)) {
                return quarantined(trade.tradeId());
            }
            QuantOrder order = orders.selectOne(new LambdaQueryWrapper<QuantOrder>()
                .eq(QuantOrder::getCredentialId, credentialId)
                .eq(QuantOrder::getSymbol, normalizedSymbol)
                .eq(QuantOrder::getExchangeOrderId, trade.orderId()));
            if (order == null || order.getClientOrderId() == null
                || !order.getClientOrderId().equals(origin.get("client_order_id"))
                || !Objects.equals(order.getStrategyId(), origin.get("strategy_id"))
                || !Objects.equals(order.getStrategyInstanceId(), origin.get("strategy_instance_id"))) {
                return quarantined(trade.tradeId());
            }
            final StrategyPositionAccounting.PositionSide side;
            final StrategyPositionAccounting.Role role;
            try {
                side = StrategyPositionAccounting.PositionSide.valueOf((String) origin.get("position_side"));
                role = StrategyPositionAccounting.Role.valueOf((String) origin.get("fill_role"));
            } catch (IllegalArgumentException invalidOrigin) {
                return quarantined(trade.tradeId());
            }
            verified.add(new VerifiedFill(order, trade, side, role));
        }

        // Do not catch projection/database failures as attribution quarantine:
        // they may indicate a gap or invariant violation and must reach caller.
        for (VerifiedFill fill : verified) {
            projector.project(credentialId, fill.order(), fill.trade(), fill.side(), fill.role());
        }
        return new ReplayResult(verified.size(), 0, List.of());
    }

    private static ReplayResult quarantined(long tradeId) {
        return new ReplayResult(0, 1, List.of(tradeId));
    }

    private record VerifiedFill(QuantOrder order, ExchangeGateway.TradeSnapshot trade,
                                StrategyPositionAccounting.PositionSide side,
                                StrategyPositionAccounting.Role role) {
    }

    /** 成交回放的投影数量及隔离成交明细。 */
    public record ReplayResult(int projected, int quarantined, List<Long> quarantineTradeIds) {
    }
}
