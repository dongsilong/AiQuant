package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Two-person recovery approval CAS. This mapper deliberately does not unlock
 * dispatch guards or send any Binance request.
 */
@Mapper
public interface LiveRecoveryApprovalMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_live_recovery_case
        (case_id, credential_id, symbol, dispatch_key, operation_type,
         requested_by, evidence_sha256, expected_exchange_order_id,
         expected_client_order_id, state, created_at)
        SELECT #{caseId}, g.credential_id, g.symbol, t.idempotency_key,
               t.operation_type, #{requesterId}, #{evidenceHash},
               t.exchange_order_id, t.client_order_id, 'PENDING', NOW(6)
        FROM quant_live_dispatch_guard g
        JOIN quant_order_dispatch_ticket t ON t.credential_id=g.credential_id
           AND t.symbol=g.symbol AND t.idempotency_key=g.active_dispatch_key
        WHERE g.credential_id=#{credentialId} AND g.symbol=#{symbol}
          AND g.active_dispatch_key=#{dispatchKey}
          AND t.dispatch_status IN ('UNKNOWN','DISPATCHING')
          AND t.operation_type IN ('OPEN','CLOSE','CANCEL')
          AND NOT EXISTS (
              SELECT 1 FROM quant_live_recovery_case c
              WHERE c.credential_id=g.credential_id AND c.symbol=g.symbol
                AND c.dispatch_key=t.idempotency_key
                AND c.state IN ('PENDING','APPROVED')
          )
        """)
    int request(@Param("caseId") long caseId, @Param("credentialId") long credentialId,
                @Param("symbol") String symbol, @Param("dispatchKey") String dispatchKey,
                @Param("requesterId") long requesterId, @Param("evidenceHash") String evidenceHash);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_recovery_case
        SET state='APPROVED', reviewed_by=#{reviewerId}, reviewed_at=NOW(6)
        WHERE case_id=#{caseId} AND state='PENDING'
          AND requested_by<>#{reviewerId}
          AND evidence_sha256=#{evidenceHash}
          AND EXISTS (
              SELECT 1 FROM quant_live_dispatch_guard g
              WHERE g.credential_id=quant_live_recovery_case.credential_id
                AND g.symbol=quant_live_recovery_case.symbol
                AND g.active_dispatch_key=quant_live_recovery_case.dispatch_key
          )
        """)
    int approve(@Param("caseId") long caseId, @Param("reviewerId") long reviewerId,
                @Param("evidenceHash") String evidenceHash);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_recovery_case
        SET state='REJECTED', reviewed_by=#{reviewerId}, reviewed_at=NOW(6)
        WHERE case_id=#{caseId} AND state='PENDING'
          AND requested_by<>#{reviewerId}
        """)
    int reject(@Param("caseId") long caseId, @Param("reviewerId") long reviewerId);
}
