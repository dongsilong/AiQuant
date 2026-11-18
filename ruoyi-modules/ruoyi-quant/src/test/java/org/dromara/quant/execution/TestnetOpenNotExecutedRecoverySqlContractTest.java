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
        assertTrue(update.contains("position_qty=0"));
        assertTrue(update.contains("initial_position_qty=0"));
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
    void legacyRecoveryRequiresAnExistingInactiveGuardAndNoDispatchTicket() throws Exception {
        String sql = resource("mapper/quant/LiveOrderDispatchMapper.xml");
        int guardStart = sql.indexOf("id=\"inactiveGuardCount\"");
        int guardEnd = sql.indexOf("</select>", guardStart);
        String guardQuery = sql.substring(guardStart, guardEnd);
        assertTrue(guardQuery.contains("quant_live_dispatch_guard"));
        assertTrue(guardQuery.contains("active_dispatch_key IS NULL"));
        int lockStart = sql.indexOf("id=\"lockGuardState\"");
        int lockEnd = sql.indexOf("</select>", lockStart);
        String lockQuery = sql.substring(lockStart, lockEnd);
        assertTrue(lockQuery.contains("FOR UPDATE"));

        int ticketStart = sql.indexOf("id=\"ticketCount\"");
        int ticketEnd = sql.indexOf("</select>", ticketStart);
        String ticketQuery = sql.substring(ticketStart, ticketEnd);
        assertTrue(ticketQuery.contains("quant_order_dispatch_ticket"));
        assertTrue(ticketQuery.contains("idempotency_key=#{key}"));
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
