package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.dromara.quant.execution.StrategyPositionAccounting.*;

@Tag("dev")
class StrategyPositionProjectionValidationTest {
    private final StrategyPositionProjectionMapper mapper = mock(StrategyPositionProjectionMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final StrategyPositionProjectionService service =
        new StrategyPositionProjectionService(mapper, manager);

    private QuantOrder order() {
        var o = new QuantOrder();
        o.setCredentialId(5L);
        o.setStrategyId(7L);
        o.setStrategyInstanceId(8L);
        o.setSymbol("BTCUSDT");
        o.setExchangeOrderId(9L);
        o.setPositionSide("LONG");
        o.setSide("BUY");
        o.setReduceOnly(false);
        return o;
    }

    private ExchangeGateway.TradeSnapshot fill() {
        return new ExchangeGateway.TradeSnapshot("BTCUSDT",11L,9L,"BUY",
            BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,
            Instant.parse("2026-10-14T02:00:00Z"));
    }

    @Test
    void rejectsAmbiguousCloseWithoutAnyDatabaseMutation() {
        var o = order();
        assertThrows(IllegalStateException.class, () ->
            service.project(5L,o,fill(),PositionSide.LONG,Role.CLOSE));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsForeignTradeAndMissingHedgeSide() {
        var o = order();
        o.setExchangeOrderId(999L);
        assertThrows(IllegalStateException.class, () ->
            service.project(5L,o,fill(),PositionSide.LONG,Role.OPEN));
        o.setExchangeOrderId(9L);
        o.setPositionSide("BOTH");
        assertThrows(IllegalStateException.class, () ->
            service.project(5L,o,fill(),PositionSide.LONG,Role.OPEN));
        verifyNoInteractions(mapper);
    }
}
