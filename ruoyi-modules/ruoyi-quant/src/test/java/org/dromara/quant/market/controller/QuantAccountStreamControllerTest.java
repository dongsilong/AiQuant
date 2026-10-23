package org.dromara.quant.market.controller;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("dev")
class QuantAccountStreamControllerTest {
    @Test
    void forwardsAlgoUpdatesWithoutRestQueries() {
        QuantAccountStreamController controller = new QuantAccountStreamController(null, null, null, null, null);
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        QuantCredential credential = new QuantCredential();
        credential.setId(1L);
        String raw = "{\"e\":\"ALGO_UPDATE\"}";
        try {
            ReflectionTestUtils.invokeMethod(controller, "send", (Consumer<Map<String, Object>>) payload::set, credential,
                new ExchangeGateway.UserDataEvent("ALGO_UPDATE", Instant.now(), raw));
            assertNotNull(payload.get());
            assertEquals("ALGO_UPDATE", payload.get().get("type"));
            assertEquals(raw, payload.get().get("rawJson"));
        } finally {
            controller.destroy();
        }
    }
}
