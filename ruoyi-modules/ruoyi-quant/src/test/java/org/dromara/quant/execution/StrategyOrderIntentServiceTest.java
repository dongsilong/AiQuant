package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyOrderIntentServiceTest {
    private final StrategyOrderIntentMapper mapper = mock(StrategyOrderIntentMapper.class);
    private final StrategyOrderIntentService service = new StrategyOrderIntentService(mapper);

    private ExchangeGateway.OrderRequest request(String side, String position, boolean reduce) {
        return new ExchangeGateway.OrderRequest(null, "BTCUSDT", side, "MARKET", BigDecimal.ONE, null, reduce, position, "cid-1", true);
    }

    private ExchangeGateway.OrderResult result() {
        return new ExchangeGateway.OrderResult(77L, "cid-1", "NEW");
    }

    @Test
    void matchedHedgePositionAndExplicitOperationCanBeRecorded() {
        when(mapper.insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        service.record(1L, 2L, 3L, "OPEN", request("BUY", "LONG", false), result());
        verify(mapper).insert(1L, "BTCUSDT", 77L, 2L, 3L, "LONG", "OPEN", "BUY", "cid-1");
    }

    @Test
    void oneWayAndWrongCloseDirectionAreRejectedWithoutWrite() {
        assertThrows(IllegalStateException.class, () -> service.record(1L, 2L, 3L, "OPEN", request("BUY", "BOTH", false), result()));
        assertThrows(IllegalStateException.class, () -> service.record(1L, 2L, 3L, "CLOSE", request("BUY", "LONG", true), result()));
        assertThrows(IllegalStateException.class, () -> service.record(1L, 2L, 3L, "CLOSE", request("SELL", "LONG", false), result()));
        verifyNoInteractions(mapper);
    }

    @Test
    void conflictingDuplicateFailsClosed() {
        when(mapper.insert(anyLong(), anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString())).thenThrow(new DuplicateKeyException(
            "collision"));
        when(mapper.find(1L, "BTCUSDT", 77L)).thenReturn(Map.of("strategy_id", 2L, "strategy_instance_id", 4L, "fill_role", "OPEN", "position_side", "LONG", "exchange_side",
            "BUY", "client_order_id", "cid-1"));
        assertThrows(IllegalStateException.class, () -> service.record(1L, 2L, 3L, "OPEN", request("BUY", "LONG", false), result()));
    }
}
