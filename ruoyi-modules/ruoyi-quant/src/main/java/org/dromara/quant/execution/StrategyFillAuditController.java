package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Privileged, read-only attribution audit. This endpoint intentionally returns
 * no calculated live position or liquidation price from lifetime buy/sell totals.
 */
@RestController
@RequestMapping("/quant/infrastructure/fill-audit")
public class StrategyFillAuditController {
    private final StrategyFillAuditService audit;

    public StrategyFillAuditController(StrategyFillAuditService audit) {
        this.audit = audit;
    }

    /**
     * 查询指定账户、策略或交易对的成交归属审计摘要。
     */
    @GetMapping("/summary")
    @SaCheckPermission("quant:allocation:view")
    public R<StrategyFillAuditService.Summary> summary(@RequestParam String credentialId, @RequestParam String strategyId, @RequestParam String instanceId,
                                                       @RequestParam String symbol) {
        return R.ok(audit.summarize(Long.valueOf(credentialId), Long.valueOf(strategyId), Long.valueOf(instanceId), symbol));
    }
}
