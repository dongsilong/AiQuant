package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.*;

/**
 * Two-person recovery approval CAS. This mapper deliberately does not unlock
 * dispatch guards or send any Binance request.
 */
@Mapper
public interface LiveRecoveryApprovalMapper {

    int request(@Param("caseId") long caseId, @Param("credentialId") long credentialId, @Param("symbol") String symbol, @Param("dispatchKey") String dispatchKey, @Param(
        "requesterId") long requesterId, @Param("evidenceHash") String evidenceHash);

    int approve(@Param("caseId") long caseId, @Param("reviewerId") long reviewerId, @Param("evidenceHash") String evidenceHash);

    int reject(@Param("caseId") long caseId, @Param("reviewerId") long reviewerId, @Param("evidenceHash") String evidenceHash);

    /**
     * Fetch the approved case, not a client-supplied order identity.
     */

    java.util.Map<String, Object> findCase(@Param("caseId") long caseId);

    /**
     * Atomic case CAS; caller must commit this together with ticket and audit.
     */

    int markExecuted(@Param("caseId") long caseId, @Param("evidenceHash") String evidenceHash);

}
