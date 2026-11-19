package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.protection.ProtectionOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The super administrator can inspect quarantined outbound orders and request
 * fact-verified protection recovery. No force-unlock action exists. All IDs are
 * represented as strings to avoid JavaScript IEEE-754 precision loss.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/infrastructure/dispatch")
public class LiveDispatchInspectionController {
    private final LiveOrderDispatchMapper mapper;
    private final ProtectionOrderService protectionOrders;

    private static String value(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : value.toString();
    }

    /**
     * 查询尚未完成确认的 LIVE 订单派发摘要。
     */
    @GetMapping("/unresolved")
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("quant:dispatch:view")
    public R<List<BlockedDispatch>> unresolved() {
        return R.ok(mapper.unresolvedDispatches().stream().map(row -> new BlockedDispatch(value(row, "credential_id"), value(row, "symbol"), value(row, "dispatch_key"),
            value(row, "operation_type"), value(row, "client_order_id"), value(row, "dispatch_status"), value(row, "exchange_order_id"), value(row, "strategy_instance_id"),
            value(row, "lease_epoch"), value(row, "blocked_at"))).toList());
    }

    /**
     * 按页面提交的完整票据身份，人工触发一次派发事实核验。
     * 返回结构化核查结果；未能确认时始终保留原有派发阻断。
     */
    @PostMapping("/recheck")
    @SaCheckRole(SystemConstants.SUPER_ADMIN_ROLE_KEY)
    @SaCheckPermission("quant:dispatch:recover")
    @Log(title = "LIVE 外发订单人工核查", businessType = BusinessType.UPDATE)
    public R<DispatchRecheckResult> recheck(@RequestBody RecheckRequest body) {
        long credentialId = parseId(body.credentialId());
        try {
            boolean recovered = protectionOrders.recheckProtectionDispatch(
                credentialId, body.symbol(), body.dispatchKey(), body.clientOrderId());
            if (recovered) {
                return R.ok(new DispatchRecheckResult(true, "RECOVERED", "RELEASE_DISPATCH_GATE",
                    "交易所事实核验完成，派发阻断已解除。"));
            }
            return R.ok(new DispatchRecheckResult(false, "RECHECK_NOT_CONFIRMED", "VERIFY_EXCHANGE_FACTS",
                "本次未能确认派发恢复条件，调度阻断保持不变。请刷新待核查列表并检查后端核查日志。"));
        } catch (IllegalStateException ex) {
            return R.ok(new DispatchRecheckResult(false, "RECHECK_BLOCKED", "BINANCE_RECONCILIATION",
                ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "核查未完成，调度阻断保持不变。" : ex.getMessage()));
        }
    }

    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Positive decimal credential ID string required");
        }
        return Long.parseLong(value);
    }

    public record RecheckRequest(String credentialId, String symbol, String dispatchKey, String clientOrderId) {
    }

    public record DispatchRecheckResult(boolean recovered, String code, String stage, String message) {
    }

    /**
     * 可安全展示的阻塞派发摘要，不包含凭证密钥或签名内容。
     */
    public record BlockedDispatch(String credentialId, String symbol, String dispatchKey, String operation, String clientOrderId, String status, String exchangeOrderId,
                                  String strategyInstanceId, String leaseEpoch, String blockedAt) {
    }
}
