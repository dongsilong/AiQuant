package org.dromara.quant.runtime;

import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicatorCalculatorTest {
    @Test
    void returnsPositiveAtrFromKlines() {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        List<ExchangeGateway.Kline> klines = List.of(
            k(base, "100", "102", "99", "101"),
            k(base.plusSeconds(60), "101", "104", "100", "103"),
            k(base.plusSeconds(120), "103", "105", "101", "102"),
            k(base.plusSeconds(180), "102", "106", "100", "105"));
        assertTrue(IndicatorCalculator.atr(klines, 2).compareTo(BigDecimal.ZERO) > 0);
    }

    private static ExchangeGateway.Kline k(Instant open, String o, String h, String l, String c) {
        return new ExchangeGateway.Kline(open, open.plusSeconds(60), bd(o), bd(h), bd(l), bd(c), BigDecimal.ONE, true);
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
