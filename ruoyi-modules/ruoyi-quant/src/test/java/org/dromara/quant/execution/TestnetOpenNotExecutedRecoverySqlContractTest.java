package org.dromara.quant.execution;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class TestnetOpenNotExecutedRecoverySqlContractTest {
    private static String resource(String name) throws IOException {
        try (InputStream input = TestnetOpenNotExecutedRecoverySqlContractTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing mapper resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void executionCanOnlyMoveUnknownOpenWithoutExchangeOrderIdToFailed() throws Exception {
        String sql = resource("mapper/quant/QuantExecutionRecordMapper.xml");
        int start = sql.indexOf("id=\"markNotExecutedAfterVerifiedAbsence\"");
        int end = sql.indexOf("</update>", start);
        String update = sql.substring(start, end);
        assertTrue(update.contains("execution_status='FAILED'"));
        assertTrue(update.contains("execution_status='UNKNOWN'"));
        assertTrue(update.contains("operation_type='OPEN'"));
        assertTrue(update.contains("exchange_order_id IS NULL"));
        assertTrue(update.contains("idempotency_key=#{key}"));
        assertTrue(update.contains("client_order_id=#{clientOrderId}"));
        assertTrue(update.contains("strategy_instance_id=#{instanceId}"));
    }

    @Test
    void positionStateCloseIsVersionedAndBoundToTheExactPendingOpen() throws Exception {
        String sql = resource("mapper/quant/QuantStrategyPositionStateMapper.xml");
        int start = sql.indexOf("id=\"closeUnexecutedOpenPending\"");
        int end = sql.indexOf("</update>", start);
        String update = sql.substring(start, end);
        assertTrue(update.contains("version=version+1"));
        assertTrue(update.contains("version=#{version}"));
        assertTrue(update.contains("status='OPEN_PENDING'"));
        assertTrue(update.contains("pending_action='OPEN'"));
        assertTrue(update.contains("pending_idempotency_key=#{key}"));
        assertTrue(update.contains("pending_client_order_id=#{clientOrderId}"));
        assertTrue(update.contains("position_cycle_key=#{cycleKey}"));
        assertTrue(update.contains("pending_order_id IS NULL"));
        assertTrue(update.contains("position_id IS NULL"));
        assertTrue(update.contains("position_qty>0"));
    }

    @Test
    void ticketCanOnlyBeMarkedNotExecutedWhileItsUnknownGuardIsStillHeld() throws Exception {
        String sql = resource("mapper/quant/LiveOrderDispatchMapper.xml");
        int start = sql.indexOf("id=\"markNotExecuted\"");
        int end = sql.indexOf("</update>", start);
        String update = sql.substring(start, end);
        assertTrue(update.contains("dispatch_status='VERIFIED_NOT_EXECUTED'"));
        assertTrue(update.contains("dispatch_status='UNKNOWN'"));
        assertTrue(update.contains("operation_type='OPEN'"));
        assertTrue(update.contains("exchange_order_id IS NULL"));
        assertTrue(update.contains("active_dispatch_key=#{key}"));
        assertTrue(update.contains("client_order_id=#{clientOrderId}"));
    }
}
