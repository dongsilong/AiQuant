package org.dromara.quant.exchange.binance;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class BinanceSymbolSettingsContractTest {
    @Test
    void readsExactLeverageAndMarginEvenForZeroQuantityRiskRows() {
        var actual = BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("BTCUSDT", true, "10")));
        assertEquals("BTCUSDT", actual.symbol());
        assertEquals("ISOLATED", actual.marginMode());
        assertEquals(10, actual.leverage());
    }

    @Test
    void hedgePositionRowsMustAgree() {
        var ok = BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("BTCUSDT", false, "20"), new PositionRiskDto("BTCUSDT", false, "20")));
        assertEquals("CROSSED", ok.marginMode());
        assertThrows(IllegalStateException.class, () -> BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("BTCUSDT", false, "20"),
            new PositionRiskDto("BTCUSDT", true, "20"))));
        assertThrows(IllegalStateException.class, () -> BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("BTCUSDT", false, "20"),
            new PositionRiskDto("BTCUSDT", false, "21"))));
    }

    @Test
    void emptyWrongSymbolOrUnreadableFactsAlwaysFailClosed() {
        assertThrows(IllegalStateException.class, () -> BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of()));
        assertThrows(IllegalStateException.class, () -> BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("ETHUSDT", true, "10"))));
        assertThrows(IllegalStateException.class, () -> BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("BTCUSDT", null, "10"))));
        assertThrows(IllegalStateException.class, () -> BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new PositionRiskDto("BTCUSDT", true, "invalid"))));
    }

    @Test
    void supportsGeneratedSdkMarginTypeAlternativeWithoutRelaxingChecks() {
        var readback = BinanceGatewayAdapter.parseSymbolTradingSettings("BTCUSDT", List.of(new MarginTypeDto()));
        assertEquals("CROSSED", readback.marginMode());
        assertEquals(12, readback.leverage());
    }

    public static class PositionRiskDto {
        private final String symbol;
        private final Boolean isolated;
        private final String leverage;

        PositionRiskDto(String symbol, Boolean isolated, String leverage) {
            this.symbol = symbol;
            this.isolated = isolated;
            this.leverage = leverage;
        }

        public String getSymbol() {
            return symbol;
        }

        public Boolean getIsolated() {
            return isolated;
        }

        public String getLeverage() {
            return leverage;
        }
    }

    public static class MarginTypeDto {
        public String getSymbol() {
            return "BTCUSDT";
        }

        public String getMarginType() {
            return "CROSSED";
        }

        public Integer getLeverage() {
            return 12;
        }
    }
}
