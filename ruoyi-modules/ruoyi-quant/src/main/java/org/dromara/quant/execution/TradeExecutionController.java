package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人工交易执行接口，所有操作最终进入统一 Execution Pipeline。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/execution")
public class TradeExecutionController {
    /**
     * 应用服务。
     */
    private final TradeExecutionApplicationService applicationService;

    /**
     * 执行减仓或全平流程。
     */
    @SaCheckPermission("quant:position:close")
    @PostMapping("/close")
    public R<ExecutionResult> close(@Valid @RequestBody ClosePositionRequest request) {
        return R.ok(applicationService.closeByUser(request));
    }

    /**
     * 执行撤单流程。
     */
    @SaCheckPermission("quant:order:cancel")
    @PostMapping("/cancel")
    public R<ExecutionResult> cancel(@Valid @RequestBody CancelOrderRequest request) {
        return R.ok(applicationService.cancelByUser(request));
    }
}
