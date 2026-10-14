package org.dromara.quant.execution;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class LiveRecoveryApprovalSqlContractTest {
    @Test
    void approvalIsTwoPersonCasAndCannotReleaseDispatchGuard() throws Exception {
        String request = String.join(" ", LiveRecoveryApprovalMapper.class
            .getMethod("request", long.class, long.class, String.class, String.class, long.class, String.class)
            .getAnnotation(Insert.class).value());
        String approve = String.join(" ", LiveRecoveryApprovalMapper.class
            .getMethod("approve", long.class, long.class, String.class)
            .getAnnotation(Update.class).value());
        assertTrue(request.contains("active_dispatch_key=#{dispatchKey}"));
        assertTrue(request.contains("t.dispatch_status IN ('UNKNOWN','DISPATCHING')"));
        assertTrue(request.contains("t.operation_type IN ('OPEN','CLOSE','CANCEL')"));
        assertTrue(request.contains("NOT EXISTS"));
        assertTrue(approve.contains("requested_by<>#{reviewerId}"));
        assertTrue(approve.contains("state='PENDING'"));
        assertTrue(approve.contains("evidence_sha256=#{evidenceHash}"));
        assertTrue(approve.contains("g.active_dispatch_key=quant_live_recovery_case.dispatch_key"));
        assertTrue(approve.contains("t.dispatch_status IN ('UNKNOWN','DISPATCHING')"));
        assertTrue(approve.contains("t.operation_type=quant_live_recovery_case.operation_type"));
        assertTrue(approve.contains("t.client_order_id=quant_live_recovery_case.expected_client_order_id"));
        assertTrue(approve.contains("t.exchange_order_id <=> quant_live_recovery_case.expected_exchange_order_id"));
        assertFalse(Arrays.stream(LiveRecoveryApprovalMapper.class.getMethods())
            .map(m -> m.getName().toLowerCase())
            .anyMatch(name -> name.contains("unlock") || name.contains("release")));
    }
}
