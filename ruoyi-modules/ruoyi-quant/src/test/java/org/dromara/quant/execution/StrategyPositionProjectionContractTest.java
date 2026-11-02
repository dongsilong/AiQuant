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
        assertTrue(xml.contains("strategy_instance_id=#{instanceId}"));
        assertTrue(xml.contains("position_side=#{side}"));

        String migrationResource = "V20261102_01__strategy_position_projection.sql";
        String migration;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(migrationResource)) {
            assertNotNull(input, migrationResource + " must be packaged");
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_inventory"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_cycle"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_fill_event"));
        assertTrue(migration.contains("UNIQUE KEY uk_quant_strategy_position_inventory (credential_id, symbol, strategy_instance_id, position_side)"));
        assertTrue(migration.contains("UNIQUE KEY uk_quant_strategy_position_fill_event (credential_id, symbol, exchange_trade_id)"));
    }
}
