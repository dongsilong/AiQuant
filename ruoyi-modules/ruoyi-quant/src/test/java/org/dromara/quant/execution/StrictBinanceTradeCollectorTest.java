package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrictBinanceTradeCollectorTest {
    private final ExchangeGateway gateway=mock(ExchangeGateway.class);
    private final StrictBinanceTradeCollector collector=new StrictBinanceTradeCollector(gateway);
    private final ExchangeGateway.Credential credential=
        new ExchangeGateway.Credential("test","secret","HMAC","LIVE");
    private final Instant start=Instant.parse("2026-10-01T00:00:00Z");
    private final Instant end=Instant.parse("2026-10-02T00:00:00Z");

    private ExchangeGateway.TradeSnapshot trade(long id) {
        return new ExchangeGateway.TradeSnapshot("BTCUSDT",id,11L,"BUY",
            BigDecimal.ONE,BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,
            start.plusSeconds(id),"USDT");
    }

    @Test
    void rejectsSaturatedPageRatherThanClaimingMissingFillsDoNotExist() {
        when(gateway.queryTrades(any(),eq("BTCUSDT"),any())).thenReturn(
            java.util.Collections.nCopies(1000,trade(1L)));
        assertThrows(IllegalStateException.class,()->
            collector.collect(credential,"BTCUSDT",start,end));
    }

    @Test
    void rejectsConflictingReplayAndEmitsChronologicalFacts() {
        when(gateway.queryTrades(any(),eq("BTCUSDT"),any()))
            .thenReturn(List.of(trade(2L),trade(1L)));
        var sorted=collector.collect(credential,"BTCUSDT",start,end);
        assertEquals(List.of(1L,2L),sorted.stream().map(ExchangeGateway.TradeSnapshot::tradeId).toList());
        assertThrows(IllegalArgumentException.class,()->
            collector.collect(credential,"BTCUSDT",end,start));
    }
}
