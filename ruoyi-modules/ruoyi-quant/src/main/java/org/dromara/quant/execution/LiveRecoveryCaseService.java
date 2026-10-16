package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Audited operator recovery CASE lifecycle, not order recovery.
 * Authentication and authorization belong to the calling boundary.
 * A case approval NEVER releases a LIVE dispatch lock.
 */
@Service
public class LiveRecoveryCaseService {
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private final LiveRecoveryApprovalMapper cases;
    private final LiveRecoveryAuditMapper audit;
    private final TransactionTemplate tx;

    public LiveRecoveryCaseService(LiveRecoveryApprovalMapper cases, LiveRecoveryAuditMapper audit, PlatformTransactionManager manager) {
        this.cases = Objects.requireNonNull(cases);
        this.audit = Objects.requireNonNull(audit);
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
        this.tx.setTimeout(5);
    }

    private static void validateCase(long caseId, long actorId, String evidence, String detail) {
        if (caseId <= 0) {
            throw new IllegalArgumentException("Invalid recovery case");
        }
        validateActor(actorId);
        validateHash(evidence);
        validateHash(detail);
    }

    private static void validateActor(long actorId) {
        if (actorId <= 0) {
            throw new IllegalArgumentException("Authenticated actor required");
        }
    }

    private static void validateHash(String value) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException("Valid SHA256 evidence digest required");
        }
    }

    /**
     * 创建绑定派发记录和证据摘要的恢复工单。
     */
    public long request(long credentialId, String symbol, String dispatchKey, long requesterId, String evidenceHash, String detailHash) {
        validateActor(requesterId);
        validateHash(evidenceHash);
        validateHash(detailHash);
        if (credentialId <= 0 || symbol == null || !symbol.matches("[A-Za-z0-9]{2,32}") || dispatchKey == null || dispatchKey.isBlank() || dispatchKey.length() > 160) {
            throw new IllegalArgumentException("Invalid recovery ticket identity");
        }
        long caseId = IdWorker.getId();
        tx.executeWithoutResult(status -> {
            if (cases.request(caseId, credentialId, symbol.toUpperCase(Locale.ROOT), dispatchKey, requesterId, evidenceHash.toLowerCase(Locale.ROOT)) != 1) {
                throw new IllegalStateException("No uniquely eligible active dispatch ticket");
            }
            append(caseId, requesterId, "REQUEST", evidenceHash, detailHash);
        });
        return caseId;
    }

    /**
     * 审批恢复工单并记录审批人的证据摘要。
     */
    public void approve(long caseId, long reviewerId, String evidenceHash, String detailHash) {
        validateCase(caseId, reviewerId, evidenceHash, detailHash);
        tx.executeWithoutResult(status -> {
            if (cases.approve(caseId, reviewerId, evidenceHash.toLowerCase(Locale.ROOT)) != 1) {
                throw new IllegalStateException("Recovery approval rejected: stale case, identical reviewer or guard mismatch");
            }
            append(caseId, reviewerId, "APPROVE", evidenceHash, detailHash);
        });
    }

    /**
     * 拒绝恢复工单并将决定写入审计链。
     */
    public void reject(long caseId, long reviewerId, String evidenceHash, String detailHash) {
        validateCase(caseId, reviewerId, evidenceHash, detailHash);
        tx.executeWithoutResult(status -> {
            if (cases.reject(caseId, reviewerId, evidenceHash.toLowerCase(Locale.ROOT)) != 1) {
                throw new IllegalStateException("Recovery rejection CAS failed");
            }
            append(caseId, reviewerId, "REJECT", evidenceHash, detailHash);
        });
    }

    private void append(long caseId, long actorId, String action, String evidenceHash, String detailHash) {
        if (audit.append(IdWorker.getId(), caseId, actorId, action, evidenceHash.toLowerCase(Locale.ROOT), detailHash.toLowerCase(Locale.ROOT)) != 1) {
            throw new IllegalStateException("Recovery audit persist failed; roll back case transition");
        }
    }
}
