package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Opt-in strict strategy position projection. The caller must prove the fill's
 * OPEN/CLOSE role from immutable execution intent; Binance BUY/SELL alone does
 * NOT establish it. No automatic reconciliation wiring is enabled here.
 *
 * An InnoDB row lock spans read / cycle / fill / inventory updates, but never
 * external HTTP. Gaps or out-of-order facts require explicit reconciliation.
 */
@Service
public class StrategyPositionProjectionService {
    private final StrategyPositionProjectionMapper mapper;
    private final TransactionTemplate tx;

    public StrategyPositionProjectionService(StrategyPositionProjectionMapper mapper,
                                             PlatformTransactionManager manager) {
        this.mapper = Objects.requireNonNull(mapper);
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
        this.tx.setTimeout(8);
    }

    public void project(long credentialId, QuantOrder verifiedOrder,
                        ExchangeGateway.TradeSnapshot trade,
                        StrategyPositionAccounting.PositionSide positionSide,
                        StrategyPositionAccounting.Role verifiedRole) {
        if (credentialId <= 0 || verifiedOrder == null || trade == null
            || verifiedOrder.getCredentialId() == null
            || verifiedOrder.getCredentialId() != credentialId
            || verifiedOrder.getStrategyId() == null || verifiedOrder.getStrategyId() <= 0
            || verifiedOrder.getStrategyInstanceId() == null || verifiedOrder.getStrategyInstanceId() <= 0
            || trade.tradeId() == null || trade.tradeId() <= 0
            || trade.orderId() == null || trade.orderId() <= 0
            || !trade.orderId().equals(verifiedOrder.getExchangeOrderId())
            || trade.symbol() == null || verifiedOrder.getSymbol() == null
            || !trade.symbol().equalsIgnoreCase(verifiedOrder.getSymbol())
            || trade.time() == null || positionSide == null || verifiedRole == null
            || verifiedOrder.getPositionSide() == null
            || !verifiedOrder.getPositionSide().equalsIgnoreCase(positionSide.name())
            || verifiedOrder.getSide() == null || !verifiedOrder.getSide().equalsIgnoreCase(trade.side())
            || verifiedRole == StrategyPositionAccounting.Role.OPEN
               && Boolean.TRUE.equals(verifiedOrder.getReduceOnly())
            || verifiedRole == StrategyPositionAccounting.Role.CLOSE
               && !Boolean.TRUE.equals(verifiedOrder.getReduceOnly())) {
            throw new IllegalStateException("Unverified strategy order, position side or OPEN/CLOSE origin");
        }
        var fact = new StrategyPositionAccounting.Fill(positionSide, verifiedRole,
            trade.side().toUpperCase(Locale.ROOT), trade.quantity(), trade.price(),
            trade.commission(), trade.realizedPnl());
        String symbol = trade.symbol().toUpperCase(Locale.ROOT);
        long strategyId = verifiedOrder.getStrategyId();
        long instanceId = verifiedOrder.getStrategyInstanceId();
        LocalDateTime when = LocalDateTime.ofInstant(trade.time(), ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            mapper.ensure(credentialId,symbol,instanceId,positionSide.name(),strategyId);
            Map<String,Object> row = mapper.lock(credentialId,symbol,instanceId,positionSide.name());
            if (row == null || asLong(row,"strategy_id") != strategyId) {
                throw new IllegalStateException("Strategy ownership changed within inventory lock");
            }
            Map<String,Object> previous = mapper.existing(credentialId,symbol,trade.tradeId());
            if (previous != null) {
                if (asLong(previous,"strategy_id") != strategyId
                    || asLong(previous,"strategy_instance_id") != instanceId
                    || asLong(previous,"exchange_order_id") != trade.orderId()
                    || !positionSide.name().equals(previous.get("position_side"))
                    || !verifiedRole.name().equals(previous.get("fill_role"))
                    || !trade.side().equalsIgnoreCase(String.valueOf(previous.get("exchange_side")))
                    || decimal(previous,"quantity").compareTo(trade.quantity()) != 0
                    || decimal(previous,"fill_price").compareTo(trade.price()) != 0
                    || decimal(previous,"commission").compareTo(trade.commission()) != 0
                    || decimal(previous,"realized_pnl").compareTo(trade.realizedPnl()) != 0
                    || !when.equals(time(previous,"trade_time"))) {
                    throw new IllegalStateException("Exchange trade replay conflicts with immutable attribution");
                }
                return;
            }
            LocalDateTime previousTime = time(row,"last_trade_time");
            Long previousId = row.get("last_trade_id") == null ? null : asLong(row,"last_trade_id");
            if (previousTime != null && (when.isBefore(previousTime)
                || when.equals(previousTime) && previousId != null && trade.tradeId() <= previousId)) {
                throw new IllegalStateException("Unordered historical fill; reconstruct from verified history first");
            }
            var current = new StrategyPositionAccounting.State(
                decimal(row,"open_quantity"),decimal(row,"cost_basis"),
                decimal(row,"realized_pnl"),decimal(row,"commissions"),asLong(row,"cycle_number"));
            var next = StrategyPositionAccounting.apply(current,fact);
            Long cycleId = row.get("cycle_id") == null ? null : asLong(row,"cycle_id");
            if (verifiedRole == StrategyPositionAccounting.Role.OPEN && current.openQuantity().signum()==0) {
                cycleId = IdWorker.getId();
                if (mapper.openCycle(cycleId,credentialId,symbol,instanceId,positionSide.name(),
                    next.cycle(),when)!=1) throw new IllegalStateException("Cannot create position cycle");
            }
            if (cycleId == null) throw new IllegalStateException("Position cycle not provable");
            if (mapper.insertFill(IdWorker.getId(),credentialId,symbol,strategyId,instanceId,
                cycleId,trade.tradeId(),trade.orderId(),positionSide.name(),verifiedRole.name(),
                trade.side().toUpperCase(Locale.ROOT),trade.quantity(),trade.price(),
                trade.commission(),trade.realizedPnl(),when)!=1) {
                throw new IllegalStateException("Cannot persist immutable fill event");
            }
            if (verifiedRole == StrategyPositionAccounting.Role.CLOSE && next.openQuantity().signum()==0) {
                if (mapper.closeCycle(cycleId,when)!=1) throw new IllegalStateException("Cannot close position cycle");
                cycleId = null;
            }
            if (mapper.update(credentialId,symbol,instanceId,positionSide.name(),strategyId,
                next.openQuantity(),next.costBasis(),next.realizedPnl(),next.commissions(),
                next.cycle(),cycleId,when,trade.tradeId())!=1) {
                throw new IllegalStateException("Inventory CAS failed");
            }
        });
    }

    private static long asLong(Map<String,Object> map,String name) {
        Object v=map.get(name);
        if (!(v instanceof Number n)) throw new IllegalStateException("Missing "+name);
        return n.longValue();
    }
    private static BigDecimal decimal(Map<String,Object> map,String name) {
        Object v=map.get(name);
        if (!(v instanceof BigDecimal n)) throw new IllegalStateException("Missing decimal "+name);
        return n;
    }
    private static LocalDateTime time(Map<String,Object> map,String name) {
        Object v=map.get(name);
        if (v==null) return null;
        if (v instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (v instanceof LocalDateTime t) return t;
        throw new IllegalStateException("Invalid "+name);
    }
}
