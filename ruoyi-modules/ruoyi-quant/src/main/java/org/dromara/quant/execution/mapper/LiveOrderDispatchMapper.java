package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Durable account+symbol dispatch fence. A claimed guard has NO TTL takeover.
 * Unknown remote outcomes can only be unlocked by a separately verified recovery.
 */
@Mapper
public interface LiveOrderDispatchMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT IGNORE INTO quant_live_dispatch_guard
            (credential_id, symbol, active_dispatch_key, updated_at)
        VALUES (#{credentialId}, #{symbol}, NULL, NOW())
        """)
    int ensureGuard(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_dispatch_guard
        SET active_dispatch_key=#{key}, updated_at=NOW()
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND active_dispatch_key IS NULL
        """)
    int claim(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
              @Param("key") String key);

    @Options(timeout = 3)
    @Insert("""
        INSERT INTO quant_order_dispatch_ticket
          (credential_id, symbol, idempotency_key, operation_type, client_order_id,
           strategy_instance_id, lease_epoch, dispatch_status, created_at, updated_at)
        VALUES (#{credentialId}, #{symbol}, #{key}, #{operation}, #{clientOrderId},
                #{instanceId}, #{epoch}, 'DISPATCHING', NOW(), NOW())
        """)
    int insertTicket(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                     @Param("key") String key, @Param("operation") String operation,
                     @Param("clientOrderId") String clientOrderId, @Param("instanceId") Long instanceId,
                     @Param("epoch") Long epoch);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_order_dispatch_ticket
        SET dispatch_status='ACKED', exchange_order_id=#{orderId}, updated_at=NOW()
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
          AND dispatch_status='DISPATCHING'
        """)
    int acknowledge(@Param("credentialId") Long credentialId, @Param("key") String key,
                    @Param("orderId") Long orderId);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_order_dispatch_ticket
        SET dispatch_status='FAILED_NOT_SENT', updated_at=NOW()
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
          AND dispatch_status='DISPATCHING'
        """)
    int notSent(@Param("credentialId") Long credentialId, @Param("key") String key);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_order_dispatch_ticket
        SET dispatch_status='UNKNOWN', updated_at=NOW()
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
          AND dispatch_status='DISPATCHING'
        """)
    int unknown(@Param("credentialId") Long credentialId, @Param("key") String key);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_dispatch_guard SET active_dispatch_key=NULL, updated_at=NOW()
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND active_dispatch_key=#{key}
        """)
    int release(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                @Param("key") String key);

    @Options(timeout = 3)
    @Select("""
        SELECT dispatch_status FROM quant_order_dispatch_ticket
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
        """)
    String ticketStatus(@Param("credentialId") Long credentialId, @Param("key") String key);
}
