package org.dromara.quant.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(xml.contains("quant_strategy_position_fill_event"));\n        assertTrue(xml.contains("quant_strategy_position_fee_event"));\n        assertTrue(xml.contains("id=\"existingFee\""));\n        assertTrue(xml.contains("id=\"insertFee\""));\n        assertTrue(xml.contains("commission_asset=#{feeAsset}"));\n        assertTrue(!xml.contains("commission_asset IS NULL OR commission_asset=#{feeAsset}"));
        assertTrue(xml.contains("quant_strategy_position_inventory"));
        assertTrue(xml.contains("strategy_instance_id=#{instanceId}"));
        assertTrue(xml.contains("id=\"ownershipSnapshot\""));
        assertTrue(xml.contains("SUM(open_quantity) AS aggregate_quantity"));
        assertTrue(xml.contains("strategy_instance_id=#{instanceId} THEN open_quantity"));
        assertTrue(xml.contains("THEN cycle_id ELSE NULL END) AS instance_cycle_id"));
        assertTrue(xml.contains("THEN cycle_number ELSE NULL END) AS instance_cycle_number"));
        assertTrue(xml.contains("position_side=#{side}"));

        Path migrationPath = Path.of("..", "..", "script", "sql", "quant", "V20261102_01__strategy_position_projection.sql");
        assertTrue(Files.isRegularFile(migrationPath), migrationPath + " must exist");
        String migration = Files.readString(migrationPath, StandardCharsets.UTF_8);
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_inventory"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_cycle"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_fill_event"));
        assertTrue(migration.contains("UNIQUE KEY uk_quant_strategy_position_inventory (credential_id, symbol, strategy_instance_id, position_side)"));
        assertTrue(migration.contains("UNIQUE KEY uk_quant_strategy_position_fill_event (credential_id, symbol, exchange_trade_id)"));

        Path feeMigrationPath = Path.of("..", "..", "script", "sql", "quant", "V20261103_01__strategy_position_fee_asset_ledger.sql");
        assertTrue(Files.isRegularFile(feeMigrationPath), feeMigrationPath + " must exist");
        String feeMigration = Files.readString(feeMigrationPath, StandardCharsets.UTF_8);
        assertTrue(feeMigration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_fee_event"));
        assertTrue(feeMigration.contains("UNIQUE KEY uk_quant_strategy_position_fee_event (credential_id, symbol, exchange_trade_id)"));
        assertTrue(feeMigration.contains("commission_asset varchar(20) NOT NULL"));
    }
}
