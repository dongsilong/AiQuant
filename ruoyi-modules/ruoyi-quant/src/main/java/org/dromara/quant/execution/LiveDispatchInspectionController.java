package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Operators can inspect quarantined outbound orders without unsafe manual unlock.
 * This endpoint deliberately has no write action. All exchange/snowflake IDs are
 * represented as strings to avoid JavaScript IEEE-754 precision loss.
 */
@RestController
@RequestMapping("/quant/infrastructure/dispatch")
public class LiveDispatchInspectionController {
    private final LiveOrderDispatchMapper mapper;

    public LiveDispatchInspectionController(LiveOrderDispatchMapper mapper) {
        this.mapper = mapper;
    }

    public record BlockedDispatch(String credentialId, String symbol, String dispatchKey,
                                  String operation, String clientOrderId, String status,
                                  String exchangeOrderId, String strategyInstanceId,
                                  String leaseEpoch, String blockedAt) {}

    @GetMapping("/unresolved")
    @SaCheckPermission("quant:dispatch:view")
    public R<List<BlockedDispatch>> unresolved() {
        return R.ok(mapper.unresolvedDispatches().stream().map(row -> new BlockedDispatch(
            value(row,"credential_id"), value(row,"symbol"), value(row,"dispatch_key"),
            value(row,"operation_type"), value(row,"client_order_id"),
            value(row,"dispatch_status"), value(row,"exchange_order_id"),
            value(row,"strategy_instance_id"), value(row,"lease_epoch"),
            value(row,"blocked_at")
        )).toList());
    }

    private static String value(Map<String,Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }
}
