package org.dromara.quant.execution;

import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class LiveRecoveryApprovalSqlContractTest {
    private static String mapper() throws IOException {
        try (InputStream input = LiveRecoveryApprovalSqlContractTest.class.getClassLoader()
            .getResourceAsStream("mapper/quant/LiveRecoveryApprovalMapper.xml")) {
            if (input == null) throw new IOException("Missing recovery approval mapper");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String statement(String xml, String id, String closingTag) {
        int start = xml.indexOf("id=\"" + id + "\"");
        int end = xml.indexOf(closingTag, start);
        if (start < 0 || end < 0) throw new IllegalStateException("Missing mapper statement: " + id);
        return xml.substring(start, end);
    }

    @Test
    void ordinaryRecoveryRemainsBoundToItsActiveTicket() throws Exception {
        String xml = mapper();
        String request = statement(xml, "request", "</insert>");
        String approve = statement(xml, "approve", "</update>");
        String markExecuted = statement(xml, "markExecuted", "</update>");
        assertTrue(request.contains("'TICKETED'"));
        assertTrue(request.contains("g.active_dispatch_key=#{dispatchKey}"));
        assertTrue(request.contains("t.dispatch_status IN ('UNKNOWN','DISPATCHING')"));
        assertTrue(approve.contains("recovery_mode='TICKETED'"));
        assertTrue(approve.contains("g.active_dispatch_key=quant_live_recovery_case.dispatch_key"));
        assertTrue(markExecuted.contains("recovery_mode='TICKETED'"));
        assertTrue(markExecuted.contains("g.active_dispatch_key=quant_live_recovery_case.dispatch_key"));
    }

    @Test
    void legacyRequestRequiresUnknownOpenPendingAndNoTicket() throws Exception {
        String xml = mapper();
        String request = statement(xml, "requestLegacyOpen", "</insert>");
        assertTrue(request.contains("'LEGACY_TESTNET_OPEN'"));
        assertTrue(request.contains("q.run_mode='TESTNET'"));
        assertTrue(request.contains("q.del_flag='0'"));
        assertTrue(request.contains("e.execution_status='UNKNOWN'"));
        assertTrue(request.contains("e.operation_type='OPEN'"));
        assertTrue(request.contains("e.exchange_order_id IS NULL"));
        assertTrue(request.contains("g.active_dispatch_key IS NULL"));
        assertTrue(request.contains("s.status='OPEN_PENDING'"));
        assertTrue(request.contains("s.pending_idempotency_key=e.idempotency_key"));
        assertTrue(request.contains("NOT EXISTS"));
        assertTrue(request.contains("quant_order_dispatch_ticket"));
    }

    @Test
    void legacyApprovalAndCompletionCannotFallThroughTicketedPath() throws Exception {
        String xml = mapper();
        String approve = statement(xml, "approve", "</update>");
        String markExecuted = statement(xml, "markExecuted", "</update>");
        assertTrue(approve.contains("recovery_mode='LEGACY_TESTNET_OPEN'"));
        assertTrue(approve.contains("e.execution_status='UNKNOWN'"));
        assertTrue(approve.contains("s.status='OPEN_PENDING'"));
        assertTrue(markExecuted.contains("recovery_mode='LEGACY_TESTNET_OPEN'"));
        assertTrue(markExecuted.contains("e.execution_status='FAILED'"));
        assertTrue(markExecuted.contains("s.status='CLOSED'"));
        assertTrue(markExecuted.contains("g.active_dispatch_key IS NULL"));
        assertTrue(markExecuted.contains("NOT EXISTS"));
    }

    @Test
    void mapperDoesNotExposeUnlockOperations() {
        assertFalse(Arrays.stream(LiveRecoveryApprovalMapper.class.getMethods())
            .map(method -> method.getName().toLowerCase())
            .anyMatch(name -> name.contains("unlock") || name.contains("release")));
    }
}
