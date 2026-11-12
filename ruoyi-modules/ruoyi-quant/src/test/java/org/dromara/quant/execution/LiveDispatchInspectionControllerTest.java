package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.protection.ProtectionOrderService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class LiveDispatchInspectionControllerTest {
    @Test
    void privilegedReadOnlyQueueDoesNotRoundSnowflakeIdentifiers() throws Exception {
        var mapper = mock(LiveOrderDispatchMapper.class);
        when(mapper.unresolvedDispatches()).thenReturn(List.of(Map.of("credential_id", 9007199254740993L, "symbol", "BTCUSDT", "dispatch_key", "key-1", "operation_type", "CANCEL"
            , "client_order_id", "exchange-id", "dispatch_status", "UNKNOWN", "exchange_order_id", 9007199254740995L, "strategy_instance_id", 9007199254740997L, "lease_epoch",
            3L, "blocked_at", "2026-10-14 10:00:00")));
        var protectionOrders = mock(ProtectionOrderService.class);
        var controller = new LiveDispatchInspectionController(mapper, protectionOrders);
        var result = controller.unresolved();
        var field = result.getClass().getMethod("getData").invoke(result);
        @SuppressWarnings("unchecked") var rows = (List<LiveDispatchInspectionController.BlockedDispatch>) field;
        assertEquals(1, rows.size());
        assertEquals("9007199254740993", rows.getFirst().credentialId());
        assertEquals("9007199254740995", rows.getFirst().exchangeOrderId());
        assertEquals("UNKNOWN", rows.getFirst().status());

        var method = LiveDispatchInspectionController.class.getMethod("unresolved");
        assertEquals("quant:dispatch:view", method.getAnnotation(SaCheckPermission.class).value()[0]);
        assertEquals(SystemConstants.SUPER_ADMIN_ROLE_KEY, method.getAnnotation(SaCheckRole.class).value()[0]);

        var recheck = LiveDispatchInspectionController.class.getMethod("recheck", LiveDispatchInspectionController.RecheckRequest.class);
        assertEquals("quant:dispatch:recover", recheck.getAnnotation(SaCheckPermission.class).value()[0]);
        assertEquals(SystemConstants.SUPER_ADMIN_ROLE_KEY, recheck.getAnnotation(SaCheckRole.class).value()[0]);
        assertEquals(BusinessType.UPDATE, recheck.getAnnotation(Log.class).businessType());

        when(protectionOrders.recheckProtectionDispatch(1L, "BTCUSDT", "key-1", "client-1")).thenReturn(true);
        var response = controller.recheck(new LiveDispatchInspectionController.RecheckRequest("1", "BTCUSDT", "key-1", "client-1"));
        assertEquals(Boolean.TRUE, response.getClass().getMethod("getData").invoke(response));
        verify(protectionOrders).recheckProtectionDispatch(1L, "BTCUSDT", "key-1", "client-1");

        assertFalse(java.util.Arrays.stream(LiveDispatchInspectionController.class.getDeclaredMethods()).anyMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains(
            "unlock")));
    }
}
