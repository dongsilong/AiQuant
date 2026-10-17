package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.*;

/**
 * MySQL 原子策略租约。唯一键 (credential_id, symbol) 是跨实例排他保证。
 * 超时仅作用于该 Mapper 方法，不修改共享 SqlSession / JdbcTemplate。
 */
@Mapper
public interface StrategyRuntimeLeaseMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT IGNORE INTO quant_strategy_runtime_lease
            (instance_id, credential_id, symbol, owner_token, lease_epoch, lease_until, create_time, update_time)
        VALUES (#{instanceId}, #{credentialId}, #{symbol}, #{ownerToken}, 1,
                DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND), NOW(), NOW())
        """)
    int ensureRow(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken, @Param(
        "leaseSeconds") int leaseSeconds);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_strategy_runtime_lease
        SET lease_epoch=CASE WHEN owner_token<>#{ownerToken} OR lease_until<=NOW()
                THEN lease_epoch+1 ELSE lease_epoch END,
            instance_id=#{instanceId}, owner_token=#{ownerToken},
            lease_until=DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND),
            update_time=NOW()
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND (owner_token=#{ownerToken} OR lease_until<NOW())
        """)
    int acquireOrRenew(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken,
                       @Param("leaseSeconds") int leaseSeconds);

    /**
     * Final outbound check: owner token AND instance identity AND unexpired lease
     * must all match. This narrows the stale-worker window, but Binance does not
     * recognize internal fencing epochs; a centralized outbound executor remains required.
     */
    @Options(timeout = 3)
    @org.apache.ibatis.annotations.Select("""
        SELECT COUNT(*) FROM quant_strategy_runtime_lease
        WHERE instance_id=#{instanceId} AND credential_id=#{credentialId}
          AND symbol=#{symbol} AND owner_token=#{ownerToken} AND lease_until>NOW()
        """)
    Long countCurrentOwner(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken);

    /**
     * Monotonic fencing epoch. This value is never reset on release or takeover.
     * Missing/expired/foreign ownership returns null; callers must fail closed.
     */
    @Options(timeout = 3)
    @org.apache.ibatis.annotations.Select("""
        SELECT lease_epoch FROM quant_strategy_runtime_lease
        WHERE instance_id=#{instanceId} AND credential_id=#{credentialId}
          AND symbol=#{symbol} AND owner_token=#{ownerToken}
          AND lease_until>NOW() AND lease_epoch>0
        """)
    Long currentEpoch(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_strategy_runtime_lease
        SET lease_until=DATE_SUB(NOW(), INTERVAL 1 SECOND), update_time=NOW()
        WHERE credential_id=#{credentialId} AND symbol=#{symbol} AND owner_token=#{ownerToken}
          AND lease_until>NOW()
        """)
    int release(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken);
}
