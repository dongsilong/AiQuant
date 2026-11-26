package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.AccountPositionSnapshotQuery;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.ExecutionRecordService;
import org.dromara.quant.execution.OrderLedgerService;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 双开轮次订单事实恢复入口。
 *
 * <p>只查询交易所订单/持仓并补齐本地订单账本，不发送订单、不撤单、不重试 UNKNOWN。</p>
 */
@RequiredArgsConstructor
@Service
public class DualHedgeOrderFactRecoveryService {
    private final StrategyHedgeRoundService roundService;
    private final ExecutionRecordService executionRecordService;
    private final QuantOrderMapper orderMapper;
    private final QuantTradeMapper tradeMapper;
    private final OrderLedgerService orderLedgerService;
    private final ExchangeGateway gateway;
    private final AccountPositionSnapshotQuery positionSnapshotQuery;
    private final StrategyPositionStateService positionStateService;

    /**
     * 对一轮 LONG/SHORT 做一次只读交易所核对。调用方必须提供当前有效凭证；
     * 本方法不从旧 Runtime 或旧凭证版本恢复交易执行权。
     */
    public RecoveryResult inspect(QuantStrategyHedgeRound round, ExchangeGateway.Credential credential) {
        if (round == null || round.getId() == null || round.getCredentialId() == null
            || round.getStrategyId() == null || round.getInstanceId() == null
            || round.getHedgeRoundKey() == null || round.getSymbol() == null || round.getSymbol().isBlank()
            || credential == null) {
            throw new IllegalArgumentException("双开订单恢复缺少轮次或有效凭证");
        }

        if (StrategyHedgeRoundService.STATUS_SIGNAL_READY.equals(round.getStatus())
            || StrategyHedgeRoundService.STATUS_CLOSED.equals(round.getStatus())
            || StrategyHedgeRoundService.STATUS_ABORTED.equals(round.getStatus())) {
            throw new IllegalStateException("当前轮次状态不允许执行订单事实恢复: " + round.getStatus());
        }

        // 先取完整持仓快照；失败时不修改任何轮次状态。
        List<ExchangeGateway.PositionSnapshot> positions =
            positionSnapshotQuery.query(round.getCredentialId(), credential);
        if (positions == null) {
            throw new IllegalStateException("交易所未返回完整持仓快照，暂停双开订单恢复");
        }
        validatePositions(positions, round.getSymbol());

        LegResult longResult = inspectLeg(round, credential, "LONG", round.getLongIdempotencyKey(),
            round.getLongClientOrderId(), round.getLongOrderId(), round.getLongLegStatus(), positions);
        LegResult shortResult = inspectLeg(round, credential, "SHORT", round.getShortIdempotencyKey(),
            round.getShortClientOrderId(), round.getShortOrderId(), round.getShortLegStatus(), positions);

        applyLeg(round, "LONG", longResult);
        applyLeg(round, "SHORT", shortResult);
        persistOpenLegState(round, "LONG", longResult);
        persistOpenLegState(round, "SHORT", shortResult);
        updateRoundStatus(round, longResult.status(), shortResult.status());
        roundService.save(round);
        return new RecoveryResult(longResult.status(), shortResult.status(), round.getStatus(),
            longResult.exchangeOrderFound(), shortResult.exchangeOrderFound());
    }

    private LegResult inspectLeg(QuantStrategyHedgeRound round, ExchangeGateway.Credential credential,
                                 String leg, String idempotencyKey, String roundClientOrderId, Long roundOrderId,
                                 String persistedLegStatus, List<ExchangeGateway.PositionSnapshot> positions) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            idempotencyKey = round.getHedgeRoundKey() + ":" + leg + ":OPEN";
        }
        QuantExecutionRecord execution = executionRecordService.find(round.getCredentialId(), idempotencyKey);
        boolean identityConflict = execution != null && (!"OPEN".equalsIgnoreCase(execution.getOperationType())
            || "FAILED".equalsIgnoreCase(execution.getExecutionStatus())
            || !round.getSymbol().equalsIgnoreCase(execution.getSymbol())
            || !round.getStrategyId().equals(execution.getStrategyId())
            || !round.getInstanceId().equals(execution.getStrategyInstanceId())
            || roundClientOrderId != null && execution.getClientOrderId() != null
                && !roundClientOrderId.equals(execution.getClientOrderId())
            || roundOrderId != null && execution.getExchangeOrderId() != null
                && !roundOrderId.equals(execution.getExchangeOrderId()));

        String clientOrderId = execution != null && execution.getClientOrderId() != null
            ? execution.getClientOrderId() : roundClientOrderId;
        Long exchangeOrderId = execution != null && execution.getExchangeOrderId() != null
            ? execution.getExchangeOrderId() : roundOrderId;
        QuantOrder localOrder = findLocalOrder(round, clientOrderId, exchangeOrderId);
        if (localOrder != null) {
            identityConflict |= !round.getSymbol().equalsIgnoreCase(localOrder.getSymbol())
                || exchangeOrderId != null && localOrder.getExchangeOrderId() != null
                    && !exchangeOrderId.equals(localOrder.getExchangeOrderId())
                || clientOrderId != null && localOrder.getClientOrderId() != null
                    && !localOrder.getClientOrderId().startsWith("EXCHANGE:")
                    && !clientOrderId.equals(localOrder.getClientOrderId())
                || localOrder.getStrategyInstanceId() != null && !round.getInstanceId().equals(localOrder.getStrategyInstanceId())
                || localOrder.getStrategyId() != null && !round.getStrategyId().equals(localOrder.getStrategyId())
                || localOrder.getPositionSide() != null && !leg.equalsIgnoreCase(localOrder.getPositionSide())
                || localOrder.getIdempotencyKey() != null
                    && !localOrder.getIdempotencyKey().equals(clientOrderId)
                    && !localOrder.getIdempotencyKey().equals(idempotencyKeyFor(round, leg))
                    && !localOrder.getIdempotencyKey().startsWith("EXCHANGE_ORDER:");
        }

        ExchangeGateway.OrderSnapshot exchangeOrder = null;
        if (exchangeOrderId != null || clientOrderId != null && !clientOrderId.isBlank()) {
            exchangeOrder = gateway.queryOrder(credential, round.getSymbol(), exchangeOrderId, clientOrderId);
        }
        if (exchangeOrder != null) {
            identityConflict |= !round.getSymbol().equalsIgnoreCase(exchangeOrder.symbol())
                || !leg.equalsIgnoreCase(exchangeOrder.positionSide())
                || !("LONG".equals(leg) ? "BUY" : "SELL").equalsIgnoreCase(exchangeOrder.side())
                || exchangeOrder.orderId() == null
                || exchangeOrderId != null && !exchangeOrderId.equals(exchangeOrder.orderId())
                || clientOrderId != null && exchangeOrder.clientOrderId() != null
                    && !clientOrderId.equals(exchangeOrder.clientOrderId());
            if (!identityConflict) {
                QuantOrder syncedOrder = orderLedgerService.syncSnapshot(round.getCredentialId(), round.getSymbol(), exchangeOrder);
                requireStrategyOwnership(round, syncedOrder);
                identityConflict = hasConflictingTradeFacts(round, leg, exchangeOrder);
                if (execution != null && !identityConflict) {
                    executionRecordService.markRecovered(execution, exchangeOrder.orderId(), exchangeOrder.status());
                }
            }
        }

        BigDecimal positionQuantity = BigDecimal.ZERO;
        ExchangeGateway.PositionSnapshot matchedPosition = null;
        for (ExchangeGateway.PositionSnapshot position : positions) {
            if (!round.getSymbol().equalsIgnoreCase(position.symbol())) {
                continue;
            }
            if (leg.equalsIgnoreCase(position.side())) {
                positionQuantity = position.quantity().abs();
                matchedPosition = position;
                break;
            }
            if ("BOTH".equalsIgnoreCase(position.side()) && position.quantity().signum() != 0) {
                identityConflict = true;
            }
        }

        String orderStatus = exchangeOrder == null ? null : exchangeOrder.status();
        BigDecimal executed = exchangeOrder == null ? null : exchangeOrder.executedQuantity();
        BigDecimal original = exchangeOrder == null ? null : exchangeOrder.originalQuantity();
        String executionStatus = execution == null ? null : execution.getExecutionStatus();
        boolean dispatchStarted = persistedLegStatus != null
            && !StrategyHedgeRoundService.LEG_NOT_STARTED.equals(persistedLegStatus);
        String status = DualHedgeLegStateResolver.resolve(dispatchStarted, executionStatus, orderStatus,
            executed, original, positionQuantity, true, identityConflict);
        return new LegResult(status, exchangeOrder == null ? exchangeOrderId : exchangeOrder.orderId(),
            exchangeOrder == null ? clientOrderId : exchangeOrder.clientOrderId(), exchangeOrder != null,
            exchangeOrder, matchedPosition);
    }

    private void persistOpenLegState(QuantStrategyHedgeRound round, String leg, LegResult result) {
        if (!DualHedgeLegStateResolver.OPEN.equals(result.status())) {
            return;
        }
        ExchangeGateway.PositionSnapshot position = result.position();
        ExchangeGateway.OrderSnapshot order = result.order();
        if (position == null || order == null
            || order.executedQuantity() == null || order.executedQuantity().signum() <= 0
            || position.entryPrice() == null || position.entryPrice().signum() <= 0
            || position.quantity() == null || position.quantity().signum() == 0) {
            throw new IllegalStateException("双开腿已核实为 OPEN，但开仓价格/数量快照不完整，拒绝生成策略持仓状态");
        }
        QuantStrategyPositionState state = positionStateService.findByInstanceAndLeg(round.getInstanceId(), leg);
        if (state == null) {
            state = new QuantStrategyPositionState();
            state.setInstanceId(round.getInstanceId());
            state.setLegKey(leg);
            state.setPositionCycleKey(round.getHedgeRoundKey() + ":" + leg);
            state.setHedgeRoundKey(round.getHedgeRoundKey());
            state.setStatus("OPEN");
            state.setDirection(leg);
            state.setEntryPrice(position.entryPrice());
            state.setPositionQty(position.quantity().abs());
            state.setInitialPositionQty(order.executedQuantity());
            state.setInitialMargin(position.margin() != null && position.margin().signum() > 0
                ? position.margin() : round.getTargetMarginPerLeg());
            state.setEntryEquitySnapshot(round.getEntryEquitySnapshot());
            state.setRealizedPnl(BigDecimal.ZERO);
            state.setFrozenLeverage(position.leverage() == null ? round.getLeverage() : position.leverage());
            state.setStrategyParamsSnapshot(round.getStrategyParamsSnapshot());
            state.setTrailingArmed(false);
            state.setTrailingStopRoi(null);
            state.setOpenedAt(order.createTime() == null ? round.getSignalEventTime()
                : LocalDateTime.ofInstant(order.createTime(), ZoneId.of("Asia/Shanghai")));
        } else {
            if (!round.getHedgeRoundKey().equals(state.getHedgeRoundKey())
                || !leg.equals(state.getLegKey()) || state.getStatus() == null
                || "CLOSED".equalsIgnoreCase(state.getStatus())
                || state.getDirection() != null && !leg.equalsIgnoreCase(state.getDirection())
                || state.getEntryPrice() != null && state.getEntryPrice().compareTo(position.entryPrice()) != 0) {
                throw new IllegalStateException("双开持仓状态与已核实轮次冲突，拒绝覆盖: hedgeRoundKey="
                    + round.getHedgeRoundKey() + ", leg=" + leg);
            }
            state.setStatus("OPEN");
            state.setDirection(leg);
            state.setPositionQty(position.quantity().abs());
            if (state.getInitialPositionQty() == null) state.setInitialPositionQty(order.executedQuantity());
            if (state.getEntryPrice() == null) state.setEntryPrice(position.entryPrice());
            if (state.getInitialMargin() == null) state.setInitialMargin(position.margin() != null && position.margin().signum() > 0
                ? position.margin() : round.getTargetMarginPerLeg());
            if (state.getEntryEquitySnapshot() == null) state.setEntryEquitySnapshot(round.getEntryEquitySnapshot());
            if (state.getFrozenLeverage() == null) state.setFrozenLeverage(position.leverage() == null ? round.getLeverage() : position.leverage());
            if (state.getPositionCycleKey() == null) state.setPositionCycleKey(round.getHedgeRoundKey() + ":" + leg);
            if (state.getRealizedPnl() == null) state.setRealizedPnl(BigDecimal.ZERO);
            if (state.getStrategyParamsSnapshot() == null) state.setStrategyParamsSnapshot(round.getStrategyParamsSnapshot());
            if (state.getTrailingArmed() == null) state.setTrailingArmed(false);
            if (state.getOpenedAt() == null) state.setOpenedAt(order.createTime() == null ? round.getSignalEventTime()
                : LocalDateTime.ofInstant(order.createTime(), ZoneId.of("Asia/Shanghai")));
        }
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(true);
        positionStateService.save(state);
    }

    private void requireStrategyOwnership(QuantStrategyHedgeRound round, QuantOrder order) {
        if (order == null || order.getId() == null) {
            throw new IllegalStateException("交易所订单已核实，但本地订单账本未返回可更新记录");
        }
        if (order.getStrategyId() == null || order.getStrategyInstanceId() == null) {
            order.setStrategyId(round.getStrategyId());
            order.setStrategyInstanceId(round.getInstanceId());
            if (orderMapper.mergeExchangeFactIfMonotonic(order) != 1) {
                throw new IllegalStateException("双开订单归属补齐失败，拒绝覆盖本地订单事实");
            }
        }
        if (!round.getStrategyId().equals(order.getStrategyId())
            || !round.getInstanceId().equals(order.getStrategyInstanceId())) {
            throw new IllegalStateException("交易所订单已核实，但本地订单策略归属与双开轮次不一致");
        }
    }

    private boolean hasConflictingTradeFacts(QuantStrategyHedgeRound round, String leg,
                                             ExchangeGateway.OrderSnapshot order) {
        List<QuantTrade> trades = tradeMapper.selectList(new LambdaQueryWrapper<QuantTrade>()
            .eq(QuantTrade::getCredentialId, round.getCredentialId())
            .eq(QuantTrade::getExchangeOrderId, order.orderId()));
        BigDecimal recordedQuantity = BigDecimal.ZERO;
        for (QuantTrade trade : trades) {
            if (!round.getSymbol().equalsIgnoreCase(trade.getSymbol())
                || trade.getPositionSide() != null && !leg.equalsIgnoreCase(trade.getPositionSide())
                || trade.getSide() != null && !("LONG".equals(leg) ? "BUY" : "SELL").equalsIgnoreCase(trade.getSide())) {
                return true;
            }
            if (trade.getQuantity() != null && trade.getQuantity().signum() > 0) {
                recordedQuantity = recordedQuantity.add(trade.getQuantity());
            }
        }
        return order.executedQuantity() != null && recordedQuantity.compareTo(order.executedQuantity()) > 0;
    }

    private QuantOrder findLocalOrder(QuantStrategyHedgeRound round, String clientOrderId, Long exchangeOrderId) {
        LambdaQueryWrapper<QuantOrder> query = new LambdaQueryWrapper<QuantOrder>()
            .eq(QuantOrder::getCredentialId, round.getCredentialId())
            .eq(QuantOrder::getSymbol, round.getSymbol());
        if (exchangeOrderId != null && clientOrderId != null) {
            query.and(wrapper -> wrapper.eq(QuantOrder::getExchangeOrderId, exchangeOrderId)
                .or().eq(QuantOrder::getClientOrderId, clientOrderId));
        } else if (exchangeOrderId != null) {
            query.eq(QuantOrder::getExchangeOrderId, exchangeOrderId);
        } else if (clientOrderId != null && !clientOrderId.isBlank()) {
            query.eq(QuantOrder::getClientOrderId, clientOrderId);
        } else {
            return null;
        }
        List<QuantOrder> matches = orderMapper.selectList(query);
        if (matches.size() > 1) {
            throw new IllegalStateException("双开腿关联到多条本地订单，标记冲突并停止恢复");
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private static void validatePositions(List<ExchangeGateway.PositionSnapshot> positions, String symbol) {
        for (ExchangeGateway.PositionSnapshot position : positions) {
            if (position == null || position.symbol() == null || position.side() == null || position.quantity() == null
                || !("LONG".equalsIgnoreCase(position.side()) || "SHORT".equalsIgnoreCase(position.side())
                    || "BOTH".equalsIgnoreCase(position.side()))) {
                throw new IllegalStateException("交易所持仓快照字段不完整，暂停双开订单恢复");
            }
            if (symbol.equalsIgnoreCase(position.symbol()) && position.quantity().signum() != 0
                && !"BOTH".equalsIgnoreCase(position.side()) && position.updateTime() == null) {
                throw new IllegalStateException("交易所持仓缺少更新时间，不能确认双开腿状态");
            }
        }
    }

    private static void applyLeg(QuantStrategyHedgeRound round, String leg, LegResult result) {
        String status = switch (result.status()) {
            case DualHedgeLegStateResolver.NOT_DISPATCHED -> StrategyHedgeRoundService.LEG_NOT_STARTED;
            case DualHedgeLegStateResolver.DISPATCHING -> StrategyHedgeRoundService.LEG_DISPATCHING;
            case DualHedgeLegStateResolver.ACCEPTED -> StrategyHedgeRoundService.LEG_ACCEPTED;
            case DualHedgeLegStateResolver.UNKNOWN, DualHedgeLegStateResolver.FILLED_POSITION_UNVERIFIED,
                DualHedgeLegStateResolver.CONFLICT -> result.status();
            case DualHedgeLegStateResolver.PARTIALLY_FILLED -> StrategyHedgeRoundService.LEG_PARTIALLY_FILLED;
            case DualHedgeLegStateResolver.OPEN -> StrategyHedgeRoundService.LEG_OPEN;
            case DualHedgeLegStateResolver.REJECTED, DualHedgeLegStateResolver.CANCELED -> result.status();
            default -> throw new IllegalStateException("未知双开腿恢复状态: " + result.status());
        };
        if ("LONG".equals(leg)) {
            round.setLongLegStatus(status);
            if (result.orderId() != null) {
                round.setLongOrderId(result.orderId());
            }
            if (result.clientOrderId() != null) {
                round.setLongClientOrderId(result.clientOrderId());
            }
        } else {
            round.setShortLegStatus(status);
            if (result.orderId() != null) {
                round.setShortOrderId(result.orderId());
            }
            if (result.clientOrderId() != null) {
                round.setShortClientOrderId(result.clientOrderId());
            }
        }
    }

    private static void updateRoundStatus(QuantStrategyHedgeRound round, String longStatus, String shortStatus) {
        if (DualHedgeLegStateResolver.OPEN.equals(longStatus) && DualHedgeLegStateResolver.OPEN.equals(shortStatus)) {
            round.setStatus(StrategyHedgeRoundService.STATUS_OPEN);
            return;
        }
        if (DualHedgeLegStateResolver.CONFLICT.equals(longStatus) || DualHedgeLegStateResolver.CONFLICT.equals(shortStatus)
            || DualHedgeLegStateResolver.UNKNOWN.equals(longStatus) || DualHedgeLegStateResolver.UNKNOWN.equals(shortStatus)
            || DualHedgeLegStateResolver.FILLED_POSITION_UNVERIFIED.equals(longStatus)
            || DualHedgeLegStateResolver.FILLED_POSITION_UNVERIFIED.equals(shortStatus)
            || DualHedgeLegStateResolver.PARTIALLY_FILLED.equals(longStatus)
            || DualHedgeLegStateResolver.PARTIALLY_FILLED.equals(shortStatus)
            || DualHedgeLegStateResolver.OPEN.equals(longStatus) || DualHedgeLegStateResolver.OPEN.equals(shortStatus)
            || DualHedgeLegStateResolver.REJECTED.equals(longStatus) || DualHedgeLegStateResolver.REJECTED.equals(shortStatus)
            || DualHedgeLegStateResolver.CANCELED.equals(longStatus) || DualHedgeLegStateResolver.CANCELED.equals(shortStatus)) {
            round.setStatus(StrategyHedgeRoundService.STATUS_DEGRADED);
            return;
        }
    }

    private static String idempotencyKeyFor(QuantStrategyHedgeRound round, String leg) {
        return round.getHedgeRoundKey() + ":" + leg + ":OPEN";
    }

    public record RecoveryResult(String longLegStatus, String shortLegStatus, String roundStatus,
                                 boolean longExchangeOrderFound, boolean shortExchangeOrderFound) {
    }

    private record LegResult(String status, Long orderId, String clientOrderId,
                             boolean exchangeOrderFound, ExchangeGateway.OrderSnapshot order,
                             ExchangeGateway.PositionSnapshot position) {
    }
}
