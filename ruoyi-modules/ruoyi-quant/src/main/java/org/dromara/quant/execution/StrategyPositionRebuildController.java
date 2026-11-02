package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

/** 管理员受权限保护的策略库存重建操作入口。 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/infrastructure/position-rebuild")
public class StrategyPositionRebuildController {
    private final StrategyPositionRebuildService rebuilds;

    @PostMapping("/start")
    @SaCheckPermission("quant:allocation:rebuild")
    public R<CreatedRun> start(@RequestBody StartRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("重建请求不能为空");
        }
        long runId = rebuilds.begin(parseId(request.credentialId()), request.symbol());
        return R.ok(new CreatedRun(Long.toString(runId)));
    }

    @PostMapping("/{runId}/process")
    @SaCheckPermission("quant:allocation:rebuild")
    public R<StrategyPositionRebuildService.RebuildProgress> process(@PathVariable String runId) {
        return R.ok(rebuilds.processNext(parseId(runId)));
    }

    @PostMapping("/{runId}/verify")
    @SaCheckPermission("quant:allocation:rebuild")
    public R<StrategyPositionRebuildService.RebuildProgress> verify(@PathVariable String runId) {
        return R.ok(rebuilds.verifyAndPromote(parseId(runId)));
    }

    @GetMapping("/{runId}")
    @SaCheckPermission("quant:allocation:rebuild")
    public R<StrategyPositionRebuildService.RebuildProgress> status(@PathVariable String runId) {
        return R.ok(rebuilds.status(parseId(runId)));
    }

    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Positive decimal ID string required");
        }
        return Long.parseLong(value);
    }

    public record StartRequest(String credentialId, String symbol) {
    }

    public record CreatedRun(String runId) {
    }
}
