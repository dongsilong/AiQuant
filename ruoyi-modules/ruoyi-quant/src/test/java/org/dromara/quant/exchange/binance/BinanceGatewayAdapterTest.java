package org.dromara.quant.exchange.binance;

import org.junit.jupiter.api.Test;
import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.QueryAllAlgoOrdersResponseInner;

import java.util.List;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BinanceGatewayAdapterTest {
    @Test
    void classifiesBinanceInvalidSymbolResponseFromWrappedSdkException() {
        ApiException invalidSymbol = new ApiException(400, "Bad Request", java.util.Map.of(), "{\"code\":-1121,\"msg\":\"Invalid symbol.\"}");
        ApiException otherError = new ApiException(400, "Bad Request", java.util.Map.of(), "{\"code\":-1003,\"msg\":\"Too many requests.\"}");

        assertTrue(BinanceGatewayAdapter.isInvalidSymbolError(new IllegalStateException("SDK", invalidSymbol)));
        assertFalse(BinanceGatewayAdapter.isInvalidSymbolError(new IllegalStateException("SDK", otherError)));
    }

    @Test
    void doesNotExpireExchangeFreshSignalWhenLocalClockIsAhead() {
        Instant localNow = Instant.parse("2026-10-17T16:03:23Z");
        Instant signalDeadline = Instant.parse("2026-10-17T16:03:22.695Z");

        assertFalse(BinanceGatewayAdapter.isOrderDeadlineExpired(signalDeadline, localNow, -3_050));
    }

    @Test
    void expiresSignalThatIsStaleByExchangeTime() {
        Instant localNow = Instant.parse("2026-10-17T16:03:23Z");
        Instant signalDeadline = Instant.parse("2026-10-17T16:03:19Z");

        assertTrue(BinanceGatewayAdapter.isOrderDeadlineExpired(signalDeadline, localNow, -3_050));
    }

    @Test
    void normalizesAlgoStatusesWithoutTreatingUnknownValuesAsKnownStates() {
        assertEquals("NEW", BinanceGatewayAdapter.normalizeAlgoStatus(" new "));
        assertEquals("TRIGGERED", BinanceGatewayAdapter.normalizeAlgoStatus("triggered"));
        assertEquals("FUTURE_STATUS", BinanceGatewayAdapter.normalizeAlgoStatus(" future_status "));
        assertNull(BinanceGatewayAdapter.normalizeAlgoStatus(null));
    }

    @Test
    void treatsUntriggeredTerminalAlgoOrderWithoutActualQuantityAsZeroFill() {
        assertEquals(java.math.BigDecimal.ZERO, BinanceGatewayAdapter.protectionExecutedQuantity("CANCELED", null));
        assertEquals(java.math.BigDecimal.ZERO, BinanceGatewayAdapter.protectionExecutedQuantity("EXPIRED", null));
        assertNull(BinanceGatewayAdapter.protectionExecutedQuantity("FINISHED", null));
        assertEquals(new java.math.BigDecimal("0.25"), BinanceGatewayAdapter.protectionExecutedQuantity("FINISHED", new java.math.BigDecimal("0.25")));
    }

    @Test
    void mapsOnlyExactRecentAlgoHistoryRecordAndConfirmsUntriggeredTerminalAsZeroFill() {
        QueryAllAlgoOrdersResponseInner other = new QueryAllAlgoOrdersResponseInner().symbol("BTCUSDT").algoId(11L).clientAlgoId("OTHER")
            .algoStatus("CANCELED").updateTime(1_760_760_000_000L);
        QueryAllAlgoOrdersResponseInner match = new QueryAllAlgoOrdersResponseInner().symbol("BTCUSDT").algoId(12L).clientAlgoId("QFTP_exact")
            .orderType("TAKE_PROFIT_MARKET").side("SELL").positionSide("BOTH").quantity("5").triggerPrice("100")
            .algoStatus("CANCELED").updateTime(1_760_760_001_000L).reduceOnly(true).closePosition(false);

        var snapshot = BinanceGatewayAdapter.findExactAlgoHistory(List.of(other, match), "BTCUSDT", null, "QFTP_exact");

        assertNotNull(snapshot);
        assertEquals(12L, snapshot.algoId());
        assertEquals("QFTP_exact", snapshot.clientAlgoId());
        assertEquals("CANCELED", snapshot.status());
        assertEquals(0, snapshot.executedQuantity().compareTo(java.math.BigDecimal.ZERO));
        assertNull(BinanceGatewayAdapter.findExactAlgoHistory(List.of(match), "BTCUSDT", null, "QFTP_other"));
    }

    @Test
    void splitsSaturatedAlgoHistoryWindowsUntilEveryWindowIsComplete() {
        var queried = new java.util.ArrayList<String>();
        var result = BinanceGatewayAdapter.collectCompleteAlgoHistory(0L, 3L, (start, end) -> {
            queried.add(start + ":" + end);
            if (end - start >= 1) {
                return java.util.Collections.nCopies(1000, new QueryAllAlgoOrdersResponseInner());
            }
            return List.of(new QueryAllAlgoOrdersResponseInner().symbol("BTCUSDT").algoId(start + 1).clientAlgoId("QFTP_" + start));
        });

        assertTrue(result.complete());
        assertEquals(4, result.orders().size());
        assertEquals(List.of("0:3", "0:1", "0:0", "1:1", "2:3", "2:2", "3:3"), queried);
    }

    @Test
    void marksAlgoHistoryIncompleteWhenOneMillisecondWindowStillHitsLimit() {
        var result = BinanceGatewayAdapter.collectCompleteAlgoHistory(100L, 100L, (start, end) ->
            java.util.Collections.nCopies(1000, new QueryAllAlgoOrdersResponseInner()));

        assertFalse(result.complete());
        assertTrue(result.orders().isEmpty());
    }
}
