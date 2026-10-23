package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Server-authenticated three-person LIVE recovery case API.
 * Never accepts an actor ID from the request. Permissions are separate for
 * requester, independent reviewer and final executor.
 * IDs are JSON strings so browsers cannot round 64-bit Snowflake IDs.
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/quant/infrastructure/recovery")
public class LiveManualRecoveryController {
    private final LiveRecoveryCaseService cases;
    private final LiveManualRecoveryExecutionService executor;
    private final LiveRecoveryAuditMapper audit;

    private static String value(Map<String, Object> row, String key) {
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

    /**
     * 根据管理员提供的证据创建人工恢复工单。
     */
    @PostMapping("/request")
    @SaCheckPermission("quant:dispatch:recovery:request")
    public R<CreatedCase> request(@RequestBody RequestCase body) {
        long id = cases.request(parseId(body.credentialId()), body.symbol(), body.dispatchKey(), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok(new CreatedCase(Long.toString(id)));
    }

    /**
     * 审批恢复工单；请求必须携带与工单匹配的证据摘要。
     */
    @PostMapping("/approve")
    @SaCheckPermission("quant:dispatch:recovery:approve")
    public R<Void> approve(@RequestBody Decision body) {
        cases.approve(parseId(body.caseId()), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok();
    }

    /**
     * 拒绝恢复工单并保留审计记录。
     */
    @PostMapping("/reject")
    @SaCheckPermission("quant:dispatch:recovery:approve")
    public R<Void> reject(@RequestBody Decision body) {
        cases.reject(parseId(body.caseId()), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok();
    }

    /**
     * 执行已审批且证据仍有效的恢复工单。
     */
    @PostMapping("/execute")
    @SaCheckPermission("quant:dispatch:recovery:execute")
    public R<Void> execute(@RequestBody Decision body) {
        executor.execute(parseId(body.caseId()), actor(), body.evidenceSha256(), body.detailSha256());
        return R.ok();
    }

    /**
     * 查询恢复工单的不可变审计事件。
     */
    @GetMapping("/{caseId}/audit")
    @SaCheckPermission("quant:dispatch:recovery:audit")
    public R<List<AuditEvent>> audit(@PathVariable String caseId) {
        // Read permission must not provide mutation rights.
        StpUtil.checkLogin();
        return R.ok(audit.listByCase(parseId(caseId)).stream().map(row -> new AuditEvent(value(row, "audit_id"), value(row, "case_id"), value(row, "actor_id"), value(row,
            "action"), value(row, "evidence_sha256"), value(row, "detail_hash"), value(row, "created_at"))).toList());
    }

    /**
     * 人工恢复工单的创建请求。
     */
    public record RequestCase(String credentialId, String symbol, String dispatchKey, String evidenceSha256, String detailSha256) {
    }

    /**
     * 管理员对恢复工单作出的决策及其证据摘要。
     */
    public record Decision(String caseId, String evidenceSha256, String detailSha256) {
    }

    /**
     * 新建恢复工单的标识。
     */
    public record CreatedCase(String caseId) {
    }

    /**
     * Audit IDs are strings to preserve long precision in browser clients.
     */
    public record AuditEvent(String auditId, String caseId, String actorId, String action, String evidenceSha256, String detailSha256, String createdAt) {
    }
}
