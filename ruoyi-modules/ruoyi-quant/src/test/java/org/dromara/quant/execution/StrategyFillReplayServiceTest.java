package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.order.domain.QuantOrder;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyFillReplayServiceTest {
    private final QuantOrderMapper orders=mock(QuantOrderMapper.class);
    private final StrategyPositionProjectionMapper mapper=mock(StrategyPositionProjectionMapper.class);
    private final StrategyPositionProjectionService projector=mock(StrategyPositionProjectionService.class);
    private final StrategyFillReplayService replay=new StrategyFillReplayService(orders,mapper,projector);

    private ExchangeGateway.TradeSnapshot trade(long id) {
        return new ExchangeGateway.TradeSnapshot("BTCUSDT",id,99L,"BUY",
            BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,
            Instant.parse("2026-10-14T02:00:00Z").plusSeconds(id));
    }

    @Test
    void incompleteHistoryFailsBeforeAnyProjectorCall() {
        assertThrows(IllegalStateException.class,()->
            replay.replay(1L,"BTCUSDT",List.of(trade(1)),false));
        verifyNoInteractions(mapper,projector);
    }

    @Test
    void firstMissingOriginQuarantinesAndHaltsOrderedReplay() {
        var result=replay.replay(1L,"BTCUSDT",List.of(trade(3),trade(2)),true);
        assertEquals(0,result.projected());
        assertEquals(List.of(2L),result.quarantineTradeIds());
        verify(mapper).verifiedIntent(1L,"BTCUSDT",99L);
        verifyNoInteractions(projector,orders);
    }    @Test
    void sortedCompleteHistoryProjectsOnlyVerifiedOrderFacts() {
        var order=new QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(99L);
        order.setClientOrderId("client-99");
        order.setStrategyId(20L);
        order.setStrategyInstanceId(30L);
        when(mapper.verifiedIntent(1L,"BTCUSDT",99L)).thenReturn(Map.of(
            "strategy_id",20L,"strategy_instance_id",30L,
            "position_side","LONG","fill_role","OPEN",
            "client_order_id","client-99"));
        when(orders.selectOne(any())).thenReturn(order);
        var outcome=replay.replay(1L,"BTCUSDT",List.of(trade(3),trade(2)),true);
        assertEquals(2,outcome.projected());
        assertEquals(0,outcome.quarantined());
        var sequence=inOrder(projector);
        sequence.verify(projector).project(1L,order,trade(2),
            StrategyPositionAccounting.PositionSide.LONG,
            StrategyPositionAccounting.Role.OPEN);
        sequence.verify(projector).project(1L,order,trade(3),
            StrategyPositionAccounting.PositionSide.LONG,
            StrategyPositionAccounting.Role.OPEN);
    }

}
