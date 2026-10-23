package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyFillReplayServiceTest {
    private final QuantOrderMapper orders = mock(QuantOrderMapper.class);
    private final StrategyPositionProjectionMapper mapper = mock(StrategyPositionProjectionMapper.class);
    private final StrategyPositionProjectionService projector = mock(StrategyPositionProjectionService.class);
    private final StrategyFillReplayService replay = new StrategyFillReplayService(orders, mapper, projector);

    private ExchangeGateway.TradeSnapshot trade(long id) {
        return new ExchangeGateway.TradeSnapshot("BTCUSDT", id, 99L, "BUY", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, Instant.parse("2026-10-14T02:00:00Z").plusSeconds(id), "USDT");
    }

    private QuantOrder verifiedOrder() {
        var order = new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(99L);
        order.setClientOrderId("client-99");
        order.setStrategyId(20L);
        order.setStrategyInstanceId(30L);
        return order;
    }

    private Map<String, Object> verifiedOrigin() {
        return Map.of("strategy_id", 20L, "strategy_instance_id", 30L, "position_side", "LONG", "fill_role", "OPEN", "client_order_id", "client-99");
    }

    @Test
    void incompleteHistoryFailsBeforeAnyProjectorCall() {
        assertThrows(IllegalStateException.class, () -> replay.replay(1L, "BTCUSDT", List.of(trade(1)), false));
        verifyNoInteractions(mapper, projector);
    }

    @Test
    void firstMissingOriginQuarantinesWithoutProjection() {
        var result = replay.replay(1L, "BTCUSDT", List.of(trade(3), trade(2)), true);
        assertEquals(0, result.projected());
        assertEquals(List.of(2L), result.quarantineTradeIds());
        verify(mapper).verifiedIntent(1L, "BTCUSDT", 99L);
        verifyNoInteractions(projector, orders);
    }

    @Test
    void laterMissingOriginPreventsProjectionOfEarlierVerifiedFills() {
        when(mapper.verifiedIntent(1L, "BTCUSDT", 99L)).thenReturn(verifiedOrigin()).thenReturn(null);
        when(orders.selectOne(any())).thenReturn(verifiedOrder());

        var result = replay.replay(1L, "BTCUSDT", List.of(trade(2), trade(3)), true);

        assertEquals(0, result.projected());
        assertEquals(1, result.quarantined());
        assertEquals(List.of(3L), result.quarantineTradeIds());
        verify(projector, never()).project(anyLong(), any(), any(), any(), any());
    }

    @Test
    void sortedCompleteHistoryProjectsOnlyVerifiedOrderFacts() {
        var order = verifiedOrder();
        when(mapper.verifiedIntent(1L, "BTCUSDT", 99L)).thenReturn(verifiedOrigin());
        when(orders.selectOne(any())).thenReturn(order);

        var outcome = replay.replay(1L, "BTCUSDT", List.of(trade(3), trade(2)), true);

        assertEquals(2, outcome.projected());
        assertEquals(0, outcome.quarantined());
        var sequence = inOrder(projector);
        sequence.verify(projector).project(1L, order, trade(2), StrategyPositionAccounting.PositionSide.LONG, StrategyPositionAccounting.Role.OPEN);
        sequence.verify(projector).project(1L, order, trade(3), StrategyPositionAccounting.PositionSide.LONG, StrategyPositionAccounting.Role.OPEN);
    }
}
