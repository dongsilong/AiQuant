package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuantMarketStreamControllerTest {
    private final QuantMarketStreamController controller = new QuantMarketStreamController(null);

    @Test
    void validatesBatchSubscriptionPairsAndLimit() {
        assertDoesNotThrow(() -> validate(List.of("BTCUSDT", "ETHUSDT"), List.of("15m", "1h")));
        assertThrows(IllegalArgumentException.class, () -> validate(List.of("BTCUSDT"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> validate(Collections.nCopies(51, "BTCUSDT"), Collections.nCopies(51, "15m")));
        assertThrows(IllegalArgumentException.class, () -> validate(List.of(" "), List.of("15m")));
    }

    @Test
    void batchEndpointRetainsTerminalPermissionAndRepeatedParameters() throws Exception {
        var method = QuantMarketStreamController.class.getMethod("streams", jakarta.servlet.http.HttpServletResponse.class, List.class, List.class,
            String.class);
        assertArrayEquals(new String[]{"quant:terminal:list"}, method.getAnnotation(SaCheckPermission.class).value());
        assertArrayEquals(new String[]{"/streams"}, method.getAnnotation(GetMapping.class).value());
    }

    @Test
    void coalescesMarkUpdatesBySymbolAndIntervalButKeepsReplayKlinesSeparate() {
        var mark = new org.dromara.quant.exchange.ExchangeGateway.MarketEvent(
            "MARK", "BTCUSDT", "1m", java.math.BigDecimal.ONE, null, java.time.Instant.now());
        var otherSymbolMark = new org.dromara.quant.exchange.ExchangeGateway.MarketEvent(
            "MARK", "ETHUSDT", "1m", java.math.BigDecimal.ONE, null, java.time.Instant.now());
        var replayKline = new org.dromara.quant.exchange.ExchangeGateway.MarketEvent(
            "KLINE", "BTCUSDT", "1m", null,
            new org.dromara.quant.exchange.ExchangeGateway.Kline(java.time.Instant.EPOCH, java.time.Instant.EPOCH,
                java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
                java.math.BigDecimal.ZERO, true),
            java.time.Instant.EPOCH, null, java.time.Instant.EPOCH, true);

        assertEquals("BTCUSDT:1m:MARK", ReflectionTestUtils.invokeMethod(controller, "latestStateKey", mark));
        assertEquals("ETHUSDT:1m:MARK", ReflectionTestUtils.invokeMethod(controller, "latestStateKey", otherSymbolMark));
        assertNull(ReflectionTestUtils.invokeMethod(controller, "latestStateKey", replayKline));
    }

    @Test
    void closesSubscriptionWhenClientHasAlreadyClosedBeforeRegistration() {
        var closed = new java.util.concurrent.atomic.AtomicBoolean(true);
        var subscriptions = new java.util.ArrayList<AutoCloseable>();
        var released = new java.util.concurrent.atomic.AtomicBoolean();

        ReflectionTestUtils.invokeMethod(controller, "addSubscription", subscriptions, closed, (AutoCloseable) () -> released.set(true));

        assertTrue(released.get());
        assertTrue(subscriptions.isEmpty());
    }

    private void validate(List<String> symbols, List<String> intervals) {
        ReflectionTestUtils.invokeMethod(controller, "validateSubscriptions", symbols, intervals);
    }
}
