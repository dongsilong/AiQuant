package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.mapper.QuantOrderMapper;
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
    }
}
