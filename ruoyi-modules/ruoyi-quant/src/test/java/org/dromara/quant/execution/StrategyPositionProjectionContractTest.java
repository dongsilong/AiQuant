package org.dromara.quant.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class StrategyPositionProjectionContractTest {
    @Test
    void inventoryUsesRowLockAndReplayIsUnique() throws Exception {
        String resource = "mapper/quant/StrategyPositionProjectionMapper.xml";
        String xml;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource + " must be packaged");
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(xml.contains("FOR UPDATE"));
        assertTrue(xml.contains("quant_strategy_order_intent"));
        assertTrue(xml.contains("exchange_order_id=#{orderId}"));
        assertTrue(xml.contains("exchange_trade_id=#{tradeId}"));
        assertTrue(xml.contains("quant_strategy_position_fill_event"));
        assertTrue(xml.contains("quant_strategy_position_inventory"));
    }
}
