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
class StrategyPositionRebuildContractTest {
    private String resource(String name) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input, name + " must be packaged");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void journalPagingIsStableAndUsesAnImmutableHighWaterMark() throws Exception {
        String xml = resource("mapper/quant/StrategyFillJournalMapper.xml");
        assertTrue(xml.contains("id=\"selectAttributedPage\""));
        assertTrue(xml.contains("id&lt;=#{maxId}"));
        assertTrue(xml.contains("trade_time&gt;#{afterTradeTime}"));
        assertTrue(xml.contains("exchange_trade_id&gt;#{afterTradeId}"));
        assertTrue(xml.contains("ORDER BY trade_time ASC,exchange_trade_id ASC"));
        assertTrue(xml.contains("attribution_status='ATTRIBUTED'"));
        assertTrue(!xml.matches("(?s).*<!\\[CDATA\\[[^]]*&(lt|gt);[^]]*\\]\\]>.*"));
    }

    @Test
    void rebuildingUsesShadowTablesAndPromotesOnlyAfterVerification() throws Exception {
        String xml = resource("mapper/quant/StrategyPositionRebuildMapper.xml");
        assertTrue(xml.contains("SELECT * FROM quant_strategy_position_rebuild_run WHERE id=#{runId} FOR UPDATE"));
        assertTrue(xml.contains("quant_strategy_position_rebuild_inventory"));
        assertTrue(xml.contains("quant_strategy_position_rebuild_fill"));
        assertTrue(xml.contains("preserved_realized_pnl"));
        assertTrue(xml.contains("preserved_cycle_number"));
        assertTrue(xml.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(xml.contains("c.closed_at IS NOT NULL"));
        assertTrue(xml.contains("c.opened_at<r.history_from"));
        assertTrue(!xml.matches("(?s).*<!\\[CDATA\\[[^]]*&(lt|gt);[^]]*\\]\\]>.*"));

        String projection = resource("mapper/quant/StrategyPositionProjectionMapper.xml");
        assertTrue(projection.contains("id=\"cycleById\""));
        String service = Files.readString(Path.of("src", "main", "java", "org", "dromara", "quant", "execution", "StrategyPositionRebuildService.java"), StandardCharsets.UTF_8);
        assertTrue(service.contains("resolveVerifiedAttribution"));
        assertTrue(service.contains("existingFee"));
        assertTrue(service.contains("verifyShadowMatchesExchange"));
        assertTrue(service.contains("Math.addExact(oldCycleNumber, 1)"));
        assertTrue(service.contains("gateway.queryPositions"));
        assertTrue(service.contains("assertSourceStable"));
        assertTrue(service.contains("rebuilds.promoteInventory(runId)"));
        assertTrue(!service.contains("projector.project("));
    }

    @Test
    void liveSchemaGateAndPermissionAreExplicit() throws Exception {
        String migration = Files.readString(Path.of("..", "..", "script", "sql", "quant", "V20261104_01__strategy_position_rebuild.sql"), StandardCharsets.UTF_8);
        assertTrue(migration.contains("ALTER TABLE quant_strategy_position_state"));
        assertTrue(migration.contains("recovery_reason"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_rebuild_run"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_rebuild_inventory"));
        assertTrue(migration.contains("CREATE TABLE IF NOT EXISTS quant_strategy_position_rebuild_fill"));
        assertTrue(migration.contains("UNIQUE KEY uk_quant_strategy_position_rebuild_run"));
        assertTrue(migration.contains("PRIMARY KEY (run_id,strategy_instance_id,position_side)"));
        assertTrue(migration.contains("PRIMARY KEY (run_id,exchange_trade_id)"));

        String permission = Files.readString(Path.of("..", "..", "script", "sql", "quant", "V20261104_02__strategy_position_rebuild_permission.sql"), StandardCharsets.UTF_8);
        assertTrue(permission.contains("quant:allocation:rebuild"));
        assertTrue(permission.contains("不自动授予任何角色"));
    }
}
