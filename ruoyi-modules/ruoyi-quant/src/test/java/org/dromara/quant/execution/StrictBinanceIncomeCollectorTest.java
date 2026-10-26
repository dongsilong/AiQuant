package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class StrictBinanceIncomeCollectorTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final StrictBinanceIncomeCollector collector = new StrictBinanceIncomeCollector(gateway);
    private final ExchangeGateway.Credential credential = new ExchangeGateway.Credential("test", "secret", "HMAC", "LIVE");
    private final Instant start = Instant.parse("2026-10-01T00:00:00Z");
    private final Instant end = Instant.parse("2026-10-02T00:00:00Z");

    private ExchangeGateway.IncomeSnapshot income(long id) {
        return new ExchangeGateway.IncomeSnapshot("BTCUSDT", "FUNDING_FEE", new BigDecimal("0.25"), "USDT", id, "funding", start.plusSeconds(id));
    }

    @Test
    void splitsSaturatedIncomePagesAndSortsDistinctFinancialFacts() {
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), argThat(range -> range.from().equals(start) && range.to().equals(end)), anyLong()))
            .thenReturn(java.util.Collections.nCopies(1000, income(1)));
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), argThat(range -> !range.from().equals(start) || !range.to().equals(end)), anyLong()))
            .thenAnswer(call -> {
                ExchangeGateway.TimeRange range = call.getArgument(3);
                return List.of(income(3), income(1)).stream()
                    .filter(item -> !item.time().isBefore(range.from()) && !item.time().isAfter(range.to())).toList();
            });

        var result = collector.collect(credential, new ExchangeGateway.TimeRange(start, end));

        assertEquals(List.of(1L, 3L), result.stream().map(ExchangeGateway.IncomeSnapshot::transactionId).toList());
    }

    @Test
    void keepsSameTransactionIdWhenIncomeTypesDiffer() {
        var funding = income(77);
        var commission = new ExchangeGateway.IncomeSnapshot("BTCUSDT", "COMMISSION", new BigDecimal("-0.02"), "USDT", 77L,
            "commission", funding.time());
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(funding, commission));

        var result = collector.collect(credential, new ExchangeGateway.TimeRange(start, end));

        assertEquals(2, result.size());
        assertEquals(List.of("COMMISSION", "FUNDING_FEE"), result.stream().map(ExchangeGateway.IncomeSnapshot::incomeType).toList());
    }

    @Test
    void rejectsSaturatedMillisecondIncomeWindowInsteadOfTruncating() {
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong()))
            .thenReturn(java.util.Collections.nCopies(1000, income(1)));
        assertThrows(IllegalStateException.class,
            () -> collector.collect(credential, new ExchangeGateway.TimeRange(start, start.plusMillis(1))));
    }

    @Test
    void reportsMissingEventTimeSeparatelyFromOutOfWindowEvent() {
        var missingTime = new ExchangeGateway.IncomeSnapshot("BTCUSDT", "FUNDING_FEE", new BigDecimal("0.25"),
            "USDT", 100L, "funding", null);
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(missingTime));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> collector.collect(credential, new ExchangeGateway.TimeRange(start, end)));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("reason=missing_event_time"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("transactionId=100"));
    }

    @Test
    void reportsOutOfWindowEventSeparately() {
        var outside = new ExchangeGateway.IncomeSnapshot("BTCUSDT", "FUNDING_FEE", new BigDecimal("0.25"),
            "USDT", 101L, "funding", end.plusMillis(1));
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(outside));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> collector.collect(credential, new ExchangeGateway.TimeRange(start, end)));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("reason=event_time_outside_requested_interval"));
    }


    @Test
    void acceptsZeroTransactionIdAndUsesStableFactIdentity() {
        var transfer = new ExchangeGateway.IncomeSnapshot("", "TRANSFER", new BigDecimal("-5.00"),
            "USDT", 0L, "TRANSFER", start.plusSeconds(1));
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(transfer));

        var result = collector.collect(credential, new ExchangeGateway.TimeRange(start, end));

        assertEquals(1, result.size());
        assertEquals(0L, result.getFirst().transactionId());
    }

    @Test
    void preservesDistinctTransferFactsThatShareZeroTransactionIdAndDeduplicatesReplay() {
        var first = new ExchangeGateway.IncomeSnapshot("", "TRANSFER", new BigDecimal("-5.00"),
            "USDT", 0L, "transfer-a", start.plusSeconds(1));
        var replay = new ExchangeGateway.IncomeSnapshot("", "TRANSFER", new BigDecimal("-5.0"),
            "USDT", 0L, "transfer-a", start.plusSeconds(1));
        var second = new ExchangeGateway.IncomeSnapshot("", "TRANSFER", new BigDecimal("-7.00"),
            "USDT", 0L, "transfer-b", start.plusSeconds(2));
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(first, replay, second));

        var result = collector.collect(credential, new ExchangeGateway.TimeRange(start, end));

        assertEquals(2, result.size());
        assertEquals(List.of(new BigDecimal("-5.00"), new BigDecimal("-7.00")),
            result.stream().map(ExchangeGateway.IncomeSnapshot::amount).toList());
    }

    @Test
    void rejectsNegativeTransactionId() {
        var invalid = new ExchangeGateway.IncomeSnapshot("BTCUSDT", "TRANSFER", new BigDecimal("-5.00"),
            "USDT", -1L, "TRANSFER", start.plusSeconds(1));
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(invalid));

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> collector.collect(credential, new ExchangeGateway.TimeRange(start, end)));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("reason=invalid_transaction_id"));
    }

    @Test
    void rejectsIncomeRowsWithMissingFinancialFacts() {
        var incomplete = new ExchangeGateway.IncomeSnapshot("BTCUSDT", "FUNDING_FEE", null, "USDT", 99L, "funding", start.plusSeconds(1));
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of(incomplete));
        assertThrows(IllegalStateException.class,
            () -> collector.collect(credential, new ExchangeGateway.TimeRange(start, end)));
    }
}
