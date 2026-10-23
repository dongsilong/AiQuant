package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
        var method = QuantMarketStreamController.class.getMethod("streams", jakarta.servlet.http.HttpServletRequest.class, jakarta.servlet.http.HttpServletResponse.class, List.class, List.class,
            String.class);
        assertArrayEquals(new String[]{"quant:terminal:list"}, method.getAnnotation(SaCheckPermission.class).value());
        assertArrayEquals(new String[]{"/streams"}, method.getAnnotation(GetMapping.class).value());
    }

    @Test
    void waitsUntilServletAsyncResponseHasStartedBeforeDeliveringEvents() throws Exception {
        var request = new MockHttpServletRequest();
        var gate = new SseResponseReadyGate(request);
        var ready = CompletableFuture.supplyAsync(gate::awaitReady);

        request.setAsyncStarted(true);

        assertTrue(ready.get(1, TimeUnit.SECONDS));
        gate.close();
    }

    @Test
    void closingBeforeAsyncResponseStartsReleasesWaitingDelivery() throws Exception {
        var gate = new SseResponseReadyGate(new MockHttpServletRequest());
        var ready = CompletableFuture.supplyAsync(gate::awaitReady);

        gate.close();

        assertFalse(ready.get(1, TimeUnit.SECONDS));
    }

    private void validate(List<String> symbols, List<String> intervals) {
        ReflectionTestUtils.invokeMethod(controller, "validateSubscriptions", symbols, intervals);
    }
}
