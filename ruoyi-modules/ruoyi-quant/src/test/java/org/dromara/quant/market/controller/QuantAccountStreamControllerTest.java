package org.dromara.quant.market.controller;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.market.UserDataStreamHub;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
class QuantAccountStreamControllerTest {
    @Test
    void forwardsAlgoUpdatesWithoutRestQueries() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        QuantAccountStreamController controller = new QuantAccountStreamController(mock(UserDataStreamHub.class), mock(QuantCredentialMapper.class), gateway,
            mock(TradeExecutionApplicationService.class));
        AtomicReference<Map<?, ?>> payload = new AtomicReference<>();
        SseEmitter emitter = new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                builder.build().forEach(item -> {
                    if (item.getData() instanceof Map<?, ?> value) {
                        payload.set(value);
                    }
                });
            }
        };
        QuantCredential credential = new QuantCredential();
        credential.setId(1L);
        String raw = "{\"e\":\"ALGO_UPDATE\"}";
        try {
            ReflectionTestUtils.invokeMethod(controller, "send", emitter, (Runnable) () -> {
            }, credential, new ExchangeGateway.UserDataEvent("ALGO_UPDATE", Instant.now(), raw));
            assertNotNull(payload.get());
            assertEquals("ALGO_UPDATE", payload.get().get("type"));
            assertEquals(raw, payload.get().get("rawJson"));
            verifyNoInteractions(gateway);
        } finally {
            controller.destroy();
        }
    }
}
