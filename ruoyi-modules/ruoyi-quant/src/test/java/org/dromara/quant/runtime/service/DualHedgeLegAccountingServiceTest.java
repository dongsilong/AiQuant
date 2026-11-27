package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.execution.StrategyOrderIntentMapper;
import org.dromara.quant.finance.domain.QuantFinancialEvent;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.reconcile.mapper.FinancialEventSyncCursorMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class DualHedgeLegAccountingServiceTest {
    private final StrategyOrderIntentMapper intentMapper = mock(StrategyOrderIntentMapper.class);
    private final QuantOrderMapper orderMapper = mock(QuantOrderMapper.class);
    private final QuantTradeMapper tradeMapper = mock(QuantTradeMapper.class);
    private final QuantFinancialEventMapper financialEventMapper = mock(QuantFinancialEventMapper.class);
    private final FinancialEventSyncCursorMapper cursorMapper = mock(FinancialEventSyncCursorMapper.class);
    private final DualHedgeLegAccountingService service = new DualHedgeLegAccountingService(
        intentMapper, orderMapper, tradeMapper, financialEventMapper, cursorMapper);
    private final LocalDateTime from = LocalDateTime.of(2026, 11, 27, 10, 0);
    private final LocalDateTime to = from.plusHours(2);

    @Test
    void summarizesOnlyCycleOwnedUsdtTradesAfterCompleteIncomeHistory() {
        when(cursorMapper.selectLastCompleteTo(1L)).thenReturn(to);
        when(financialEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(intentMapper.findOrdersByCycle(1L, "BTCUSDT", 2L, "cycle-long", "LONG"))
            .thenReturn(List.of(Map.of("orderId", 101L, "fillRole", "OPEN",
                "positionSide", "LONG", "exchangeSide", "BUY")));
        when(orderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(order(101L, "BUY", "LONG", "2")));
        when(tradeMapper.selectList(any(Wrapper.class))).thenReturn(List.of(trade(101L, 9001L, "BUY", "LONG", "2")));

        var result = service.summarize(1L, "BTCUSDT", 2L, "cycle-long", "LONG", from, to);

        assertTrue(result.complete());
        assertEquals("COMPLETE", result.status());
        assertEquals(new BigDecimal("1.25"), result.realizedPnl());
        assertEquals(new BigDecimal("0.10"), result.commission());
        assertEquals(new BigDecimal("1.15"), result.netAdjustment());
        assertEquals(BigDecimal.ZERO, result.fundingFee());
        assertEquals(1, result.orderCount());
        assertEquals(1, result.tradeCount());
    }

    @Test
    void blocksWhenFundingExistsBecauseItCannotBeAttributedToEitherHedgeLeg() {
        when(cursorMapper.selectLastCompleteTo(1L)).thenReturn(to);
        when(financialEventMapper.selectList(any(Wrapper.class))).thenReturn(List.of(new QuantFinancialEvent()));

        var result = service.summarize(1L, "BTCUSDT", 2L, "cycle-long", "LONG", from, to);

        assertFalse(result.complete());
        assertEquals("UNATTRIBUTED_FUNDING_FEE", result.status());
        verifyNoInteractions(intentMapper, orderMapper, tradeMapper);
    }

    @Test
    void blocksWhenIncomeHistoryCursorDoesNotCoverRequestedInterval() {
        when(cursorMapper.selectLastCompleteTo(1L)).thenReturn(to.minusSeconds(1));

        var result = service.summarize(1L, "BTCUSDT", 2L, "cycle-long", "LONG", from, to);

        assertFalse(result.complete());
        assertEquals("FINANCIAL_HISTORY_INCOMPLETE", result.status());
        verifyNoInteractions(financialEventMapper, intentMapper, orderMapper, tradeMapper);
    }

    private QuantOrder order(long orderId, String side, String positionSide, String executed) {
        QuantOrder order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(orderId);
        order.setSide(side);
        order.setPositionSide(positionSide);
        order.setExecutedQuantity(new BigDecimal(executed));
        return order;
    }

    private QuantTrade trade(long orderId, long tradeId, String side, String positionSide, String quantity) {
        QuantTrade trade = new QuantTrade();
        trade.setCredentialId(1L);
        trade.setSymbol("BTCUSDT");
        trade.setExchangeOrderId(orderId);
        trade.setExchangeTradeId(tradeId);
        trade.setSide(side);
        trade.setPositionSide(positionSide);
        trade.setQuantity(new BigDecimal(quantity));
        trade.setPrice(new BigDecimal("50000"));
        trade.setCommission(new BigDecimal("0.10"));
        trade.setCommissionAsset("USDT");
        trade.setRealizedPnl(new BigDecimal("1.25"));
        trade.setTradeTime(from.plusMinutes(30));
        return trade;
    }
}
