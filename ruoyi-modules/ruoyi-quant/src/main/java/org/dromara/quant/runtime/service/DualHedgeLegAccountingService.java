package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.execution.StrategyOrderIntentMapper;
import org.dromara.quant.finance.domain.QuantFinancialEvent;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.reconcile.mapper.FinancialEventSyncCursorMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 汇总双开单腿的已实现盈亏和手续费。任何无法精确归属的事实都会阻断完整性。
 * 时间参数及财务同步游标均使用 UTC LocalDateTime。
 */
@Service
@RequiredArgsConstructor
public class DualHedgeLegAccountingService {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String USDT = "USDT";

    private final StrategyOrderIntentMapper orderIntentMapper;
    private final QuantOrderMapper orderMapper;
    private final QuantTradeMapper tradeMapper;
    private final QuantFinancialEventMapper financialEventMapper;
    private final FinancialEventSyncCursorMapper incomeSyncCursorMapper;

    public AccountingResult summarize(long credentialId, String symbol, long instanceId, String positionCycleKey,
                                      String positionSide, LocalDateTime openedAt, LocalDateTime asOf) {
        String normalizedSymbol = symbol == null ? "" : symbol.toUpperCase(Locale.ROOT);
        String leg = positionSide == null ? "" : positionSide.toUpperCase(Locale.ROOT);
        if (credentialId <= 0 || instanceId <= 0 || normalizedSymbol.isBlank()
            || positionCycleKey == null || positionCycleKey.isBlank()
            || !Set.of("LONG", "SHORT").contains(leg) || openedAt == null || asOf == null
            || !openedAt.isBefore(asOf)) {
            throw new IllegalArgumentException("双开腿账务查询参数无效");
        }
        if (!normalizedSymbol.endsWith(USDT)) {
            return blocked("UNSUPPORTED_QUOTE_ASSET");
        }

        LocalDateTime incomeCursor = incomeSyncCursorMapper.selectLastCompleteTo(credentialId);
        if (incomeCursor == null || incomeCursor.isBefore(asOf)) {
            return blocked("FINANCIAL_HISTORY_INCOMPLETE");
        }
        List<QuantFinancialEvent> fundingEvents = financialEventMapper.selectList(
            new LambdaQueryWrapper<QuantFinancialEvent>()
                .eq(QuantFinancialEvent::getCredentialId, credentialId)
                .eq(QuantFinancialEvent::getSymbol, normalizedSymbol)
                .eq(QuantFinancialEvent::getEventType, "FUNDING_FEE")
                .ge(QuantFinancialEvent::getEventTime, openedAt)
                .le(QuantFinancialEvent::getEventTime, asOf));
        if (!fundingEvents.isEmpty()) {
            return blocked("UNATTRIBUTED_FUNDING_FEE");
        }

        List<Map<String, Object>> intents = orderIntentMapper.findOrdersByCycle(
            credentialId, normalizedSymbol, instanceId, positionCycleKey, leg);
        if (intents == null || intents.isEmpty()) {
            return blocked("NO_ORDER_INTENT");
        }
        Map<Long, String> expectedSides = new LinkedHashMap<>();
        for (Map<String, Object> intent : intents) {
            if (!(intent.get("orderId") instanceof Number orderId)
                || orderId.longValue() <= 0
                || !leg.equals(intent.get("positionSide"))
                || !(("OPEN".equals(intent.get("fillRole")) && expectedOpenSide(leg).equals(intent.get("exchangeSide")))
                    || ("CLOSE".equals(intent.get("fillRole")) && expectedCloseSide(leg).equals(intent.get("exchangeSide"))))) {
                return blocked("ORDER_INTENT_CONFLICT");
            }
            if (expectedSides.putIfAbsent(orderId.longValue(), (String) intent.get("exchangeSide")) != null) {
                return blocked("DUPLICATE_ORDER_INTENT");
            }
        }

        List<QuantOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<QuantOrder>()
            .eq(QuantOrder::getCredentialId, credentialId)
            .eq(QuantOrder::getSymbol, normalizedSymbol)
            .in(QuantOrder::getExchangeOrderId, expectedSides.keySet()));
        Map<Long, QuantOrder> ordersById = new HashMap<>();
        for (QuantOrder order : orders) {
            if (order.getExchangeOrderId() == null || ordersById.putIfAbsent(order.getExchangeOrderId(), order) != null
                 || !normalizedSymbol.equalsIgnoreCase(order.getSymbol())
                || !Objects.equals(instanceId, order.getStrategyInstanceId())
                || !leg.equalsIgnoreCase(order.getPositionSide())
                || !Objects.equals(expectedSides.get(order.getExchangeOrderId()), upper(order.getSide()))
                || order.getExecutedQuantity() == null || order.getExecutedQuantity().signum() < 0
                || order.getStatus() == null || !Set.of("NEW", "PARTIALLY_FILLED", "FILLED", "CANCELED", "EXPIRED", "REJECTED")
                    .contains(order.getStatus().toUpperCase(Locale.ROOT))) {
                return blocked("ORDER_LEDGER_CONFLICT");
            }
        }
        if (ordersById.size() != expectedSides.size()) {
            return blocked("ORDER_LEDGER_INCOMPLETE");
        }

        List<QuantTrade> trades = tradeMapper.selectList(new LambdaQueryWrapper<QuantTrade>()
            .eq(QuantTrade::getCredentialId, credentialId)
            .eq(QuantTrade::getSymbol, normalizedSymbol)
            .in(QuantTrade::getExchangeOrderId, expectedSides.keySet()));
        Map<Long, QuantTrade> uniqueTrades = new HashMap<>();
        Map<Long, BigDecimal> executedByOrder = new HashMap<>();
        for (QuantTrade trade : trades) {
            if (trade.getExchangeTradeId() == null || trade.getExchangeOrderId() == null
                || !expectedSides.containsKey(trade.getExchangeOrderId())
                || !normalizedSymbol.equalsIgnoreCase(trade.getSymbol())
                || !leg.equalsIgnoreCase(trade.getPositionSide())
                || !Objects.equals(expectedSides.get(trade.getExchangeOrderId()), upper(trade.getSide()))
                || trade.getQuantity() == null || trade.getQuantity().signum() <= 0
                || trade.getPrice() == null || trade.getPrice().signum() <= 0
                || trade.getTradeTime() == null || trade.getTradeTime().isBefore(openedAt)
                || trade.getTradeTime().isAfter(asOf)
                || trade.getCommission() == null || !USDT.equalsIgnoreCase(trade.getCommissionAsset())
                || trade.getRealizedPnl() == null) {
                return blocked("TRADE_FACT_INCOMPLETE_OR_CONFLICTING");
            }
            QuantTrade previous = uniqueTrades.putIfAbsent(trade.getExchangeTradeId(), trade);
            if (previous != null) {
                if (!sameTrade(previous, trade)) {
                    return blocked("DUPLICATE_TRADE_ID_CONFLICT");
                }
                continue;
            }
            executedByOrder.merge(trade.getExchangeOrderId(), trade.getQuantity(), BigDecimal::add);
        }

        for (Map.Entry<Long, QuantOrder> entry : ordersById.entrySet()) {
            BigDecimal recorded = executedByOrder.getOrDefault(entry.getKey(), ZERO);
            if (recorded.compareTo(entry.getValue().getExecutedQuantity()) != 0) {
                return blocked("ORDER_TRADE_QUANTITY_MISMATCH");
            }
        }

        BigDecimal realizedPnl = ZERO;
        BigDecimal commission = ZERO;
        for (QuantTrade trade : uniqueTrades.values()) {
            realizedPnl = realizedPnl.add(trade.getRealizedPnl());
            commission = commission.add(trade.getCommission());
        }
        return new AccountingResult(true, "COMPLETE", realizedPnl, commission,
            realizedPnl.subtract(commission), ZERO, expectedSides.size(), uniqueTrades.size());
    }

    private static AccountingResult blocked(String status) {
        return new AccountingResult(false, status, null, null, null, null, 0, 0);
    }

    private static String expectedOpenSide(String leg) {
        return "LONG".equals(leg) ? "BUY" : "SELL";
    }

    private static String expectedCloseSide(String leg) {
        return "LONG".equals(leg) ? "SELL" : "BUY";
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static boolean sameTrade(QuantTrade left, QuantTrade right) {
        return Objects.equals(left.getExchangeOrderId(), right.getExchangeOrderId())
            && Objects.equals(left.getSymbol(), right.getSymbol())
            && Objects.equals(left.getPositionSide(), right.getPositionSide())
            && Objects.equals(left.getSide(), right.getSide())
            && decimalEquals(left.getQuantity(), right.getQuantity())
            && decimalEquals(left.getPrice(), right.getPrice())
            && decimalEquals(left.getCommission(), right.getCommission())
            && Objects.equals(left.getCommissionAsset(), right.getCommissionAsset())
            && decimalEquals(left.getRealizedPnl(), right.getRealizedPnl())
            && Objects.equals(left.getTradeTime(), right.getTradeTime());
    }

    private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    public record AccountingResult(boolean complete, String status, BigDecimal realizedPnl,
                                   BigDecimal commission, BigDecimal netAdjustment, BigDecimal fundingFee,
                                   int orderCount, int tradeCount) {
    }
}
