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
 * NOT establish it. The fill journal invokes this projector only after exact strategy attribution succeeds.
 * <p>
 * An InnoDB row lock spans read / cycle / fill / inventory updates, but never
 * external HTTP. Gaps or out-of-order facts require explicit reconciliation.
 */
@Service
public class StrategyPositionProjectionService {
    private final StrategyPositionProjectionMapper mapper;
    private final TransactionTemplate tx;

    public StrategyPositionProjectionService(StrategyPositionProjectionMapper mapper, PlatformTransactionManager manager) {
        this.mapper = Objects.requireNonNull(mapper);
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
        this.tx.setTimeout(8);
    }

    private static long asLong(Map<String, Object> map, String name) {
        Object v = map.get(name);
        if (!(v instanceof Number n)) {
            throw new IllegalStateException("Missing " + name);
        }
        return n.longValue();
    }

    private static BigDecimal decimal(Map<String, Object> map, String name) {
        Object v = map.get(name);
        if (!(v instanceof BigDecimal n)) {
            throw new IllegalStateException("Missing decimal " + name);
        }
        return n;
    }

    private static LocalDateTime time(Map<String, Object> map, String name) {
        Object v = map.get(name);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        if (v instanceof LocalDateTime t) {
            return t;
        }
        throw new IllegalStateException("Invalid " + name);
    }

    /**
     * Require this strategy instance to own the complete exchange-side exposure before the
     * legacy account-position runtime is allowed to manage it. Shared net exposure is quarantined.
     */
    public boolean matchesExclusiveInventory(long credentialId, String symbol, long strategyId, long instanceId, String side, BigDecimal accountQuantity) {
        if (credentialId <= 0 || symbol == null || symbol.isBlank() || strategyId <= 0 || instanceId <= 0
            || side == null || !(side.equalsIgnoreCase("LONG") || side.equalsIgnoreCase("SHORT"))
            || accountQuantity == null || accountQuantity.signum() < 0) {
            return false;
        }
        Map<String, Object> snapshot = mapper.ownershipSnapshot(credentialId, symbol.trim().toUpperCase(Locale.ROOT), instanceId, side.toUpperCase(Locale.ROOT));
        if (snapshot == null || decimal(snapshot, "instance_quantity").compareTo(accountQuantity) != 0
            || decimal(snapshot, "aggregate_quantity").compareTo(accountQuantity) != 0) {
            return false;
        }
        Object owner = snapshot.get("instance_strategy_id");
        if (accountQuantity.signum() == 0) {
            return owner == null || owner instanceof Number number && number.longValue() == strategyId && snapshot.get("instance_cycle_id") == null;
        }
        return owner instanceof Number number && number.longValue() == strategyId
            && snapshot.get("instance_cycle_id") instanceof Number cycleId && cycleId.longValue() > 0
            && snapshot.get("instance_cycle_number") instanceof Number cycleNumber && cycleNumber.longValue() > 0;
    }

    /**
     * Verify strategy ownership before writing any downstream allocation ledger.
     */
    public void verifyAttribution(long credentialId, QuantOrder verifiedOrder, ExchangeGateway.TradeSnapshot trade) {
        resolveIntent(credentialId, verifiedOrder, trade);
    }

    /**
     * 重新验证成交对应的不可变订单意图，并返回受服务端意图约束的持仓方向和开平角色。
     */
    public VerifiedAttribution resolveVerifiedAttribution(long credentialId, QuantOrder verifiedOrder, ExchangeGateway.TradeSnapshot trade) {
        Map<String, Object> intent = resolveIntent(credentialId, verifiedOrder, trade);
        try {
            return new VerifiedAttribution(
                StrategyPositionAccounting.PositionSide.valueOf(String.valueOf(intent.get("position_side"))),
                StrategyPositionAccounting.Role.valueOf(String.valueOf(intent.get("fill_role"))));
        } catch (IllegalArgumentException invalidIntent) {
            throw new IllegalStateException("Immutable strategy execution intent has an unsupported position side or fill role", invalidIntent);
        }
    }

    /** 不可变订单意图验证后得到的成交归属。 */
    public record VerifiedAttribution(StrategyPositionAccounting.PositionSide positionSide, StrategyPositionAccounting.Role role) {
    }

    /**
     * Resolve OPEN/CLOSE and LONG/SHORT only from the immutable accepted-order intent.
     */
    public void projectAttributed(long credentialId, QuantOrder verifiedOrder, ExchangeGateway.TradeSnapshot trade) {
        projectAttributed(credentialId, verifiedOrder, trade, resolveVerifiedAttribution(credentialId, verifiedOrder, trade));
    }

    /** 已核验的不可变成交归属可在同一处理链路内复用，避免重复解析订单意图。 */
    void projectAttributed(long credentialId, QuantOrder verifiedOrder, ExchangeGateway.TradeSnapshot trade,
                           VerifiedAttribution attribution) {
        if (attribution == null) {
            throw new IllegalStateException("Missing verified strategy fill attribution");
        }
        project(credentialId, verifiedOrder, trade, attribution.positionSide(), attribution.role());
    }

    private Map<String, Object> resolveIntent(long credentialId, QuantOrder verifiedOrder, ExchangeGateway.TradeSnapshot trade) {
        if (credentialId <= 0 || verifiedOrder == null || trade == null || trade.symbol() == null || trade.orderId() == null || trade.orderId() <= 0
            || trade.side() == null || verifiedOrder.getCredentialId() == null || verifiedOrder.getCredentialId() != credentialId
            || verifiedOrder.getStrategyId() == null || verifiedOrder.getStrategyId() <= 0
            || verifiedOrder.getStrategyInstanceId() == null || verifiedOrder.getStrategyInstanceId() <= 0
            || verifiedOrder.getClientOrderId() == null || verifiedOrder.getClientOrderId().isBlank()
            || !trade.orderId().equals(verifiedOrder.getExchangeOrderId()) || verifiedOrder.getSymbol() == null
            || !trade.symbol().equalsIgnoreCase(verifiedOrder.getSymbol())) {
            throw new IllegalStateException("Missing exact strategy order or exchange fill identity");
        }
        Map<String, Object> intent = mapper.verifiedIntent(credentialId, trade.symbol().toUpperCase(Locale.ROOT), trade.orderId());
        if (intent == null) {
            throw new IllegalStateException("No immutable strategy execution intent for exchange fill");
        }
        if (asLong(intent, "strategy_id") != verifiedOrder.getStrategyId()
            || asLong(intent, "strategy_instance_id") != verifiedOrder.getStrategyInstanceId()
            || verifiedOrder.getPositionSide() == null || !verifiedOrder.getPositionSide().equalsIgnoreCase(String.valueOf(intent.get("position_side")))
            || !verifiedOrder.getClientOrderId().equals(intent.get("client_order_id"))
            || !trade.side().equalsIgnoreCase(String.valueOf(intent.get("exchange_side")))
            || verifiedOrder.getSide() == null || !trade.side().equalsIgnoreCase(verifiedOrder.getSide())) {
            throw new IllegalStateException("策略订单意图与成交新事实冲突");
        }
        StrategyPositionAccounting.PositionSide positionSide;
        StrategyPositionAccounting.Role role;
        try {
            positionSide = StrategyPositionAccounting.PositionSide.valueOf(String.valueOf(intent.get("position_side")));
            role = StrategyPositionAccounting.Role.valueOf(String.valueOf(intent.get("fill_role")));
        } catch (IllegalArgumentException invalidIntent) {
            throw new IllegalStateException("Immutable strategy execution intent has an unsupported position side or fill role", invalidIntent);
        }
        String expectedSide = (positionSide == StrategyPositionAccounting.PositionSide.LONG) == (role == StrategyPositionAccounting.Role.OPEN) ? "BUY" : "SELL";
        if (!expectedSide.equalsIgnoreCase(trade.side())
            || role == StrategyPositionAccounting.Role.OPEN && Boolean.TRUE.equals(verifiedOrder.getReduceOnly())
            || role == StrategyPositionAccounting.Role.CLOSE && !Boolean.TRUE.equals(verifiedOrder.getReduceOnly())) {
            throw new IllegalStateException("策略订单意图与成交新事实冲突：订单方向或 reduce-only 状态不匹配");
        }
        return intent;
    }

    private void persistFeeEvent(long credentialId, String symbol, long strategyId, long instanceId, long cycleId, long tradeId, long orderId,
                                 String side, BigDecimal fee, String feeAsset, LocalDateTime tradeTime) {
        Map<String, Object> existing = mapper.existingFee(credentialId, symbol, tradeId);
        if (existing != null) {
            if (asLong(existing, "strategy_id") != strategyId || asLong(existing, "strategy_instance_id") != instanceId
                || asLong(existing, "cycle_id") != cycleId || asLong(existing, "exchange_order_id") != orderId
                || !side.equals(existing.get("position_side")) || decimal(existing, "commission").compareTo(fee) != 0
                || !feeAsset.equals(existing.get("commission_asset")) || !tradeTime.equals(time(existing, "trade_time"))) {
                throw new IllegalStateException("Strategy fee event conflicts with immutable exchange fill");
            }
            return;
        }
        if (mapper.insertFee(IdWorker.getId(), credentialId, symbol, strategyId, instanceId, cycleId, tradeId, orderId,
            side, fee, feeAsset, tradeTime) != 1) {
            throw new IllegalStateException("Cannot persist immutable strategy fee event");
        }
    }

    /**
     * 将归属确定的成交投影到账户内对应的策略持仓状态。
     */
    public void project(long credentialId, QuantOrder verifiedOrder, ExchangeGateway.TradeSnapshot trade, StrategyPositionAccounting.PositionSide positionSide,
                        StrategyPositionAccounting.Role verifiedRole) {
        if (credentialId <= 0 || verifiedOrder == null || trade == null || verifiedOrder.getCredentialId() == null || verifiedOrder.getCredentialId() != credentialId || verifiedOrder.getStrategyId() == null || verifiedOrder.getStrategyId() <= 0 || verifiedOrder.getStrategyInstanceId() == null || verifiedOrder.getStrategyInstanceId() <= 0 || trade.tradeId() == null || trade.tradeId() <= 0 || trade.orderId() == null || trade.orderId() <= 0 || !trade.orderId().equals(verifiedOrder.getExchangeOrderId()) || trade.symbol() == null || verifiedOrder.getSymbol() == null || !trade.symbol().equalsIgnoreCase(verifiedOrder.getSymbol()) || trade.time() == null || trade.side() == null || !(trade.side().equalsIgnoreCase("BUY") || trade.side().equalsIgnoreCase("SELL")) || trade.quantity() == null || trade.price() == null || trade.commission() == null || trade.realizedPnl() == null || positionSide == null || verifiedRole == null || verifiedOrder.getPositionSide() == null || !verifiedOrder.getPositionSide().equalsIgnoreCase(positionSide.name()) || verifiedOrder.getSide() == null || !verifiedOrder.getSide().equalsIgnoreCase(trade.side()) || verifiedRole == StrategyPositionAccounting.Role.OPEN && Boolean.TRUE.equals(verifiedOrder.getReduceOnly()) || verifiedRole == StrategyPositionAccounting.Role.CLOSE && !Boolean.TRUE.equals(verifiedOrder.getReduceOnly())) {
            throw new IllegalStateException("Unverified strategy order, position side or OPEN/CLOSE origin");
        }
        // Commission amounts remain in their Binance-reported asset. The fee event
        // ledger stores each fill independently; inventory totals never mix currencies.
        String feeAsset = trade.commissionAsset() == null ? "" : trade.commissionAsset().toUpperCase(Locale.ROOT);
        if (!feeAsset.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalStateException("Missing authenticated Binance commission asset");
        }
        var fact = new StrategyPositionAccounting.Fill(positionSide, verifiedRole, trade.side().toUpperCase(Locale.ROOT), trade.quantity(), trade.price(), trade.commission(),
            trade.realizedPnl());
        String symbol = trade.symbol().toUpperCase(Locale.ROOT);
        long strategyId = verifiedOrder.getStrategyId();
        long instanceId = verifiedOrder.getStrategyInstanceId();
        LocalDateTime when = LocalDateTime.ofInstant(trade.time(), ZoneOffset.UTC);
        tx.executeWithoutResult(status -> {
            // Never trust a requested role or mutable QuantOrder alone. Intent
            // must originate from a separately persisted exact-order record.
            Map<String, Object> intent = mapper.verifiedIntent(credentialId, symbol, trade.orderId());
            if (intent == null || asLong(intent, "strategy_id") != strategyId || asLong(intent, "strategy_instance_id") != instanceId || !positionSide.name().equals(intent.get(
                "position_side")) || !verifiedRole.name().equals(intent.get("fill_role")) || !trade.side().equalsIgnoreCase(String.valueOf(intent.get("exchange_side"))) || verifiedOrder.getClientOrderId() == null || !verifiedOrder.getClientOrderId().equals(intent.get("client_order_id"))) {
                throw new IllegalStateException("No immutable, matching strategy execution intent");
            }
            mapper.ensure(credentialId, symbol, instanceId, positionSide.name(), strategyId);
            Map<String, Object> row = mapper.lock(credentialId, symbol, instanceId, positionSide.name());
            if (row == null || asLong(row, "strategy_id") != strategyId) {
                throw new IllegalStateException("Strategy ownership changed within inventory lock");
            }
            Map<String, Object> previous = mapper.existing(credentialId, symbol, trade.tradeId());
            if (previous != null) {
                if (asLong(previous, "strategy_id") != strategyId || asLong(previous, "strategy_instance_id") != instanceId || asLong(previous, "exchange_order_id") != trade.orderId() || !positionSide.name().equals(previous.get("position_side")) || !verifiedRole.name().equals(previous.get("fill_role")) || !trade.side().equalsIgnoreCase(String.valueOf(previous.get("exchange_side"))) || decimal(previous, "quantity").compareTo(trade.quantity()) != 0 || decimal(previous, "fill_price").compareTo(trade.price()) != 0 || decimal(previous, "commission").compareTo(trade.commission()) != 0 || !feeAsset.equals(previous.get("commission_asset")) || decimal(previous, "realized_pnl").compareTo(trade.realizedPnl()) != 0 || !when.equals(time(previous, "trade_time"))) {
                    throw new IllegalStateException("Exchange trade replay conflicts with immutable attribution");
                }
                persistFeeEvent(credentialId, symbol, strategyId, instanceId, asLong(previous, "cycle_id"), trade.tradeId(), trade.orderId(),
                    positionSide.name(), trade.commission(), feeAsset, when);
                return;
            }
            LocalDateTime previousTime = time(row, "last_trade_time");
            Long previousId = row.get("last_trade_id") == null ? null : asLong(row, "last_trade_id");
            if (previousTime != null && (when.isBefore(previousTime) || when.equals(previousTime) && previousId != null && trade.tradeId() <= previousId)) {
                throw new IllegalStateException("Unordered historical fill; reconstruct from verified history first");
            }
            var current = new StrategyPositionAccounting.State(decimal(row, "open_quantity"), decimal(row, "cost_basis"), decimal(row, "realized_pnl"), BigDecimal.ZERO,
                asLong(row, "cycle_number"));
            var next = StrategyPositionAccounting.apply(current, fact);
            Long cycleId = row.get("cycle_id") == null ? null : asLong(row, "cycle_id");
            if (verifiedRole == StrategyPositionAccounting.Role.OPEN && current.openQuantity().signum() == 0) {
                cycleId = IdWorker.getId();
                if (mapper.openCycle(cycleId, credentialId, symbol, instanceId, positionSide.name(), next.cycle(), when) != 1) {
                    throw new IllegalStateException("Cannot create position cycle");
                }
            }
            if (cycleId == null) {
                throw new IllegalStateException("Position cycle not provable");
            }
            if (mapper.insertFill(IdWorker.getId(), credentialId, symbol, strategyId, instanceId, cycleId, trade.tradeId(), trade.orderId(), positionSide.name(),
                verifiedRole.name(), trade.side().toUpperCase(Locale.ROOT), trade.quantity(), trade.price(), trade.commission(), feeAsset, trade.realizedPnl(), when) != 1) {
                throw new IllegalStateException("Cannot persist immutable fill event");
            }
            persistFeeEvent(credentialId, symbol, strategyId, instanceId, cycleId, trade.tradeId(), trade.orderId(), positionSide.name(),
                trade.commission(), feeAsset, when);
            if (verifiedRole == StrategyPositionAccounting.Role.CLOSE && next.openQuantity().signum() == 0) {
                if (mapper.closeCycle(cycleId, when) != 1) {
                    throw new IllegalStateException("Cannot close position cycle");
                }
                cycleId = null;
            }
            if (mapper.update(credentialId, symbol, instanceId, positionSide.name(), strategyId, next.openQuantity(), next.costBasis(), next.realizedPnl(),
                next.cycle(), cycleId, when, trade.tradeId()) != 1) {
                throw new IllegalStateException("Inventory CAS failed");
            }
        });
    }
}
