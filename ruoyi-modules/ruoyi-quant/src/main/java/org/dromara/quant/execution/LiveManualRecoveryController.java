package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.domain.R;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import java.util.List;
import java.util.Map;
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
    private final LiveRecoveryAuditMapper audit;

    public LiveManualRecoveryController(LiveRecoveryCaseService cases,
                                        LiveManualRecoveryExecutionService executor,
                                        LiveRecoveryAuditMapper audit) {
        this.cases = cases;
        this.executor = executor;
        this.audit = audit;
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

    /** Audit IDs are strings to preserve long precision in browser clients. */
    public record AuditEvent(String auditId, String caseId, String actorId,
                             String action, String evidenceSha256,
                             String detailSha256, String createdAt) {}

    @GetMapping("/{caseId}/audit")
    @SaCheckPermission("quant:dispatch:recovery:audit")
    public R<List<AuditEvent>> audit(@PathVariable String caseId) {
        // Read permission must not provide mutation rights.
        StpUtil.checkLogin();
        return R.ok(audit.listByCase(parseId(caseId)).stream().map(row ->
            new AuditEvent(value(row, "audit_id"), value(row, "case_id"),
                value(row, "actor_id"), value(row, "action"),
                value(row, "evidence_sha256"), value(row, "detail_hash"),
                value(row, "created_at"))).toList());
    }

    private static String value(Map<String,Object> row, String key) {
        Object raw = row.get(key);
        return raw == null ? null : raw.toString();
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
