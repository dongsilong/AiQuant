package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.*;

/**
 * Insert-only evidence ledger. Application DB role must not have UPDATE or
 * DELETE permission on this table; external WORM archival remains required.
 */
@Mapper
public interface LiveRecoveryAuditMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_live_recovery_audit
            (audit_id, case_id, actor_id, action, evidence_sha256, detail_hash, created_at)
        VALUES (#{auditId}, #{caseId}, #{actorId}, #{action}, #{evidenceHash},
                #{detailHash}, NOW(6))
        """)
    int append(@Param("auditId") long auditId, @Param("caseId") long caseId, @Param("actorId") long actorId, @Param("action") String action,
               @Param("evidenceHash") String evidenceHash, @Param("detailHash") String detailHash);

    /**
     * Read-only evidence lookup. No UPDATE or DELETE method is exposed.
     */
    @Options(timeout = 3)
    @Select("""
        SELECT audit_id, case_id, actor_id, action,
               evidence_sha256, detail_hash, created_at
        FROM quant_live_recovery_audit
        WHERE case_id=#{caseId}
        ORDER BY created_at ASC, audit_id ASC
        LIMIT 100
        """)
    java.util.List<java.util.Map<String, Object>> listByCase(@Param("caseId") long caseId);
}

