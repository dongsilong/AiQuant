package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderLedgerServiceTest {
    @Test
    void historicalSnapshotEnrichesPositionSideWithoutOverwritingRequestReduceOnly() {
        QuantOrder existing = order();
        QuantOrderMapper mapper = mock(QuantOrderMapper.class);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.mergeExchangeFactIfMonotonic(any())).thenReturn(1);

        OrderLedgerService service = new OrderLedgerService(mapper);
        ExchangeGateway.OrderSnapshot snapshot = snapshot(true);

        QuantOrder result = service.syncHistoricalSnapshot(10L, "ETHUSDT", snapshot);

        assertSame(existing, result);
        assertEquals("BOTH", result.getPositionSide());
        assertFalse(result.getReduceOnly());
        verify(mapper).mergeExchangeFactIfMonotonic(argThat(fact ->
            "BOTH".equals(fact.getPositionSide()) && Boolean.FALSE.equals(fact.getReduceOnly())));
    }

    @Test
    void normalSnapshotStillRejectsReduceOnlyConflict() {
        QuantOrderMapper mapper = mock(QuantOrderMapper.class);
        when(mapper.selectOne(any())).thenReturn(order());
        OrderLedgerService service = new OrderLedgerService(mapper);

        assertThrows(IllegalStateException.class, () -> service.syncSnapshot(10L, "ETHUSDT", snapshot(true)));
        verify(mapper, never()).mergeExchangeFactIfMonotonic(any());
    }

    @Test
    void historicalSnapshotDoesNotReportSuccessWhenPositionSideWasNotPersisted() {
        QuantOrder existing = order();
        QuantOrder current = order();
        QuantOrderMapper mapper = mock(QuantOrderMapper.class);
        when(mapper.selectOne(any())).thenReturn(existing);
        when(mapper.mergeExchangeFactIfMonotonic(any())).thenReturn(0);
        when(mapper.selectById(1L)).thenReturn(current);
        OrderLedgerService service = new OrderLedgerService(mapper);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> service.syncHistoricalSnapshot(10L, "ETHUSDT", snapshot(false)));

        assertTrue(failure.getMessage().contains("positionSide"));
    }

    private static QuantOrder order() {
        QuantOrder order = new QuantOrder();
        order.setId(1L);
        order.setCredentialId(10L);
        order.setSymbol("ETHUSDT");
        order.setExchangeOrderId(100L);
        order.setClientOrderId("web-client");
        order.setStatus("FILLED");
        order.setPositionSide(null);
        order.setReduceOnly(false);
        order.setExecutedQuantity(new BigDecimal("0.021"));
        return order;
    }

    private static ExchangeGateway.OrderSnapshot snapshot(boolean reduceOnly) {
        return new ExchangeGateway.OrderSnapshot("ETHUSDT", 100L, "web-client", "SELL", "MARKET", "FILLED",
            BigDecimal.ZERO, new BigDecimal("0.021"), new BigDecimal("0.021"),
            Instant.parse("2026-11-02T08:40:29Z"), BigDecimal.ZERO, "BOTH", reduceOnly);
    }
}
