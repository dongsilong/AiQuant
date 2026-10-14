package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Strict verified-fill replay boundary. All exchange queries happen upstream.
 * This never guesses an external/manual fill's strategy, fills a gap, or
 * reassigns trade ownership from aggregate positions.
 *
 * This is deliberately an explicit, bounded replay API, not an automatic
 * always-on Binance poller. Each fill commits through the idempotent projector.
 */
@Service
public class StrategyFillReplayService {
    private final QuantOrderMapper orders;
    private final StrategyPositionProjectionMapper provenance;
    private final StrategyPositionProjectionService projector;

    public StrategyFillReplayService(QuantOrderMapper orders,
                                     StrategyPositionProjectionMapper provenance,
                                     StrategyPositionProjectionService projector) {
        this.orders=Objects.requireNonNull(orders);
        this.provenance=Objects.requireNonNull(provenance);
        this.projector=Objects.requireNonNull(projector);
    }

    public record ReplayResult(int projected, int quarantined, List<Long> quarantineTradeIds) {}

    /**
     * Only call after verifying the exchange history window is complete.
     * A missing historical page, ambiguous order or unsupported accounting
     * mode makes the whole batch ineligible. No partial unsafe attribution.
     */
    public ReplayResult replay(long credentialId, String symbol,
                               List<ExchangeGateway.TradeSnapshot> exchangeFills,
                               boolean completeHistoryVerified) {
        if (credentialId<=0 || symbol==null || symbol.isBlank()
            || exchangeFills==null || !completeHistoryVerified) {
            throw new IllegalStateException("Complete, authenticated fill history required");
        }
        if (exchangeFills.size()>1000) {
            throw new IllegalArgumentException("Replay must be bounded and paged");
        }
        List<ExchangeGateway.TradeSnapshot> sorted=new ArrayList<>(exchangeFills);
        if (sorted.stream().anyMatch(t->t==null || t.time()==null || t.tradeId()==null
            || t.tradeId()<=0 || !symbol.equalsIgnoreCase(t.symbol()) || t.orderId()==null)) {
            throw new IllegalStateException("Invalid Binance fill in replay window");
        }
        sorted.sort(Comparator.comparing(ExchangeGateway.TradeSnapshot::time)
            .thenComparing(ExchangeGateway.TradeSnapshot::tradeId));
        int projected=0;
        List<Long> quarantined=new ArrayList<>();
        for (var t: sorted) {
            Map<String,Object> origin=provenance.verifiedIntent(credentialId,
                symbol.toUpperCase(java.util.Locale.ROOT),t.orderId());
            if (origin==null || !(origin.get("position_side") instanceof String)
                || !(origin.get("fill_role") instanceof String)) {
                quarantined.add(t.tradeId());
                break;
            }
            QuantOrder order=orders.selectOne(new LambdaQueryWrapper<QuantOrder>()
                .eq(QuantOrder::getCredentialId,credentialId)
                .eq(QuantOrder::getSymbol,symbol.toUpperCase(java.util.Locale.ROOT))
                .eq(QuantOrder::getExchangeOrderId,t.orderId()));
            if (order==null || order.getClientOrderId()==null
                || !order.getClientOrderId().equals(origin.get("client_order_id"))
                || !Objects.equals(order.getStrategyId(),origin.get("strategy_id"))
                || !Objects.equals(order.getStrategyInstanceId(),origin.get("strategy_instance_id"))) {
                quarantined.add(t.tradeId());
                break;
            }
            try {
                var side=StrategyPositionAccounting.PositionSide.valueOf(
                    (String) origin.get("position_side"));
                var role=StrategyPositionAccounting.Role.valueOf(
                    (String) origin.get("fill_role"));
                projector.project(credentialId,order,t,side,role);
                projected++;
            } catch (IllegalArgumentException invalidOrigin) {
                quarantined.add(t.tradeId());
                break;
            }
            // SQL, inconsistency, and time-gap failures stop replay. Never
            // silently advance a checkpoint past a failed projection.
        }
        return new ReplayResult(projected,quarantined.size(),List.copyOf(quarantined));
    }
}
