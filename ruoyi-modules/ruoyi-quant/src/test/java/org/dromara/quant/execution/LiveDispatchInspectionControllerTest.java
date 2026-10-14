package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LiveDispatchInspectionControllerTest {
    @Test
    void privilegedReadOnlyQueueDoesNotRoundSnowflakeIdentifiers() throws Exception {
        var mapper = mock(LiveOrderDispatchMapper.class);
        when(mapper.unresolvedDispatches()).thenReturn(List.of(Map.of(
            "credential_id", 9007199254740993L,
            "symbol", "BTCUSDT",
            "dispatch_key", "key-1",
            "operation_type", "CANCEL",
            "client_order_id", "exchange-id",
            "dispatch_status", "UNKNOWN",
            "exchange_order_id", 9007199254740995L,
            "strategy_instance_id", 9007199254740997L,
            "lease_epoch", 3L,
            "blocked_at", "2026-10-14 10:00:00"
        )));
        var controller = new LiveDispatchInspectionController(mapper);
        var result = controller.unresolved();
        var field = result.getClass().getMethod("getData").invoke(result);
        @SuppressWarnings("unchecked")
        var rows = (List<LiveDispatchInspectionController.BlockedDispatch>) field;
        assertEquals(1, rows.size());
        assertEquals("9007199254740993", rows.getFirst().credentialId());
        assertEquals("9007199254740995", rows.getFirst().exchangeOrderId());
        assertEquals("UNKNOWN", rows.getFirst().status());

        var method = LiveDispatchInspectionController.class.getMethod("unresolved");
        assertEquals("quant:dispatch:view",
            method.getAnnotation(SaCheckPermission.class).value()[0]);
        assertFalse(java.util.Arrays.stream(LiveDispatchInspectionController.class.getDeclaredMethods())
            .anyMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains("unlock")));
    }
}
