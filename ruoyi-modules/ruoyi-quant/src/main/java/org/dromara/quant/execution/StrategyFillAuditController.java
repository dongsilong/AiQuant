package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Privileged, read-only attribution audit. This endpoint intentionally returns
 * no calculated live position or liquidation price from lifetime buy/sell totals.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/infrastructure/fill-audit")
public class StrategyFillAuditController {
    private final StrategyFillAuditService audit;
    private final StrategyFillJournalService journal;

    public R<StrategyFillAuditService.Summary> summary(@RequestParam String credentialId, @RequestParam String strategyId, @RequestParam String instanceId,
                                                       @RequestParam String symbol) {
        return R.ok(audit.summarize(Long.valueOf(credentialId), Long.valueOf(strategyId), Long.valueOf(instanceId), symbol));
    }

    /** 查询未归属或归属冲突的原始成交事实。 */
    @GetMapping("/unresolved")
    @SaCheckPermission("quant:allocation:view")
    public R<List<StrategyFillJournalService.UnresolvedFill>> unresolved(@RequestParam String credentialId, @RequestParam(required = false) String symbol,
                                                                         @RequestParam(defaultValue = "100") int limit) {
        return R.ok(journal.unresolved(Long.valueOf(credentialId), symbol, limit));
    }
}
