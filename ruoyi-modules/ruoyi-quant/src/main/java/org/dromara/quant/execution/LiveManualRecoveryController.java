package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.*;

/**
 * Server-authenticated three-person LIVE recovery case API.
 * Never accepts an actor ID from the request. Permissions are separate for
 * requester, independent reviewer and final executor.
 * IDs are JSON strings so browsers cannot round 64-bit Snowflake IDs.
 */
@RestController
@RequestMapping("/quant/infrastructure/recovery")
public class LiveManualRecoveryController {
    private final LiveRecoveryCaseService cases;
    private final LiveManualRecoveryExecutionService executor;

    public LiveManualRecoveryController(LiveRecoveryCaseService cases,
                                        LiveManualRecoveryExecutionService executor) {
        this.cases = cases;
        this.executor = executor;
    }

    public record RequestCase(String credentialId, String symbol, String dispatchKey,
                              String evidenceSha256, String detailSha256) {}
    public record Decision(String caseId, String evidenceSha256, String detailSha256) {}
    public record CreatedCase(String caseId) {}

    @PostMapping("/request")
    @SaCheckPermission("quant:dispatch:recovery:request")
    public R<CreatedCase> request(@RequestBody RequestCase body) {
        long id = cases.request(parseId(body.credentialId()), body.symbol(), body.dispatchKey(),
            actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok(new CreatedCase(Long.toString(id)));
    }

    @PostMapping("/approve")
    @SaCheckPermission("quant:dispatch:recovery:approve")
    public R<Void> approve(@RequestBody Decision body) {
        cases.approve(parseId(body.caseId()), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok();
    }

    @PostMapping("/reject")
    @SaCheckPermission("quant:dispatch:recovery:approve")
    public R<Void> reject(@RequestBody Decision body) {
        cases.reject(parseId(body.caseId()), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok();
    }

    @PostMapping("/execute")
    @SaCheckPermission("quant:dispatch:recovery:execute")
    public R<Void> execute(@RequestBody Decision body) {
        executor.execute(parseId(body.caseId()), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok();
    }

    private static long actor() {
        return StpUtil.getLoginIdAsLong();
    }

    private static long parseId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("Positive decimal ID string required");
        }
        return Long.parseLong(value);
    }
}
