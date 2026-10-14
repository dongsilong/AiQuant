package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

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
    int append(@Param("auditId") long auditId, @Param("caseId") long caseId,
               @Param("actorId") long actorId, @Param("action") String action,
               @Param("evidenceHash") String evidenceHash,
               @Param("detailHash") String detailHash);
}
