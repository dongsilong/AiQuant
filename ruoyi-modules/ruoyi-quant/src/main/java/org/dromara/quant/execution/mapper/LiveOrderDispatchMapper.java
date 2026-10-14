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
    @Update("""
        UPDATE quant_order_dispatch_ticket
        SET dispatch_status='RECOVERED', exchange_order_id=#{orderId}, updated_at=NOW()
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
          AND symbol=#{symbol} AND client_order_id=#{clientOrderId}
          AND (exchange_order_id IS NULL OR exchange_order_id=#{orderId})
          AND dispatch_status IN ('DISPATCHING','UNKNOWN')
        """)
    int recoverVerified(@Param("credentialId") Long credentialId,
                        @Param("symbol") String symbol, @Param("key") String key,
                        @Param("clientOrderId") String clientOrderId,
                        @Param("orderId") Long orderId);

    /**
     * Only the active account-symbol slot is eligible for fact-proven protection recovery.
     * It must match the exact client algo ID and operation type.
     */
    @Options(timeout = 3)
    @Select("""
        SELECT g.active_dispatch_key
        FROM quant_live_dispatch_guard g
        JOIN quant_order_dispatch_ticket t ON t.credential_id=g.credential_id
          AND t.idempotency_key=g.active_dispatch_key AND t.symbol=g.symbol
        WHERE g.credential_id=#{credentialId} AND g.symbol=#{symbol}
          AND t.client_order_id=#{clientOrderId} AND t.operation_type=#{operation}
          AND t.dispatch_status IN ('DISPATCHING','UNKNOWN')
        """)
    String activeProtectionTicket(@Param("credentialId") Long credentialId,
                                  @Param("symbol") String symbol,
                                  @Param("clientOrderId") String clientOrderId,
                                  @Param("operation") String operation);

    @Options(timeout = 3)
    @Select("""
        SELECT dispatch_status FROM quant_order_dispatch_ticket
        WHERE credential_id=#{credentialId} AND idempotency_key=#{key}
        """)
    String ticketStatus(@Param("credentialId") Long credentialId, @Param("key") String key);
    /** Read-only operator queue, intentionally no TTL-based recovery or unlock. */
    @Options(timeout = 3)
    @Select("""
        SELECT g.credential_id AS credential_id, g.symbol AS symbol,
               g.active_dispatch_key AS dispatch_key, g.updated_at AS blocked_at,
               t.operation_type AS operation_type, t.client_order_id AS client_order_id,
               t.dispatch_status AS dispatch_status, t.exchange_order_id AS exchange_order_id,
               t.strategy_instance_id AS strategy_instance_id,
               t.lease_epoch AS lease_epoch
        FROM quant_live_dispatch_guard g
        LEFT JOIN quant_order_dispatch_ticket t
          ON t.credential_id=g.credential_id AND t.idempotency_key=g.active_dispatch_key
        WHERE g.active_dispatch_key IS NOT NULL
        ORDER BY g.updated_at ASC
        LIMIT 100
        """)
    java.util.List<java.util.Map<String,Object>> unresolvedDispatches();

}
