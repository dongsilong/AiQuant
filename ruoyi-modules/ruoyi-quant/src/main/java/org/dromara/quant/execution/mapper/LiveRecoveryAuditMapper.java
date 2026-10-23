package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.*;

/**
 * Insert-only evidence ledger. Application DB role must not have UPDATE or
 * DELETE permission on this table; external WORM archival remains required.
 */
@Mapper
public interface LiveRecoveryAuditMapper {

    int append(@Param("auditId") long auditId, @Param("caseId") long caseId, @Param("actorId") long actorId, @Param("action") String action,
               @Param("evidenceHash") String evidenceHash, @Param("detailHash") String detailHash);

    /**
     * Read-only evidence lookup. No UPDATE or DELETE method is exposed.
     */

    java.util.List<java.util.Map<String, Object>> listByCase(@Param("caseId") long caseId);
}

