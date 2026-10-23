package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.*;

/**
 * MySQL 原子策略租约。唯一键 (credential_id, symbol) 是跨实例排他保证。
 * 超时仅作用于该 Mapper 方法，不修改共享 SqlSession / JdbcTemplate。
 */
@Mapper
public interface StrategyRuntimeLeaseMapper {

    int ensureRow(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken, @Param(
        "leaseSeconds") int leaseSeconds);

    int acquireOrRenew(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken,
                       @Param("leaseSeconds") int leaseSeconds);

    /**
     * Final outbound check: owner token AND instance identity AND unexpired lease
     * must all match. This narrows the stale-worker window, but Binance does not
     * recognize internal fencing epochs; a centralized outbound executor remains required.
     */
    Long countCurrentOwner(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken);

    /**
     * Monotonic fencing epoch. This value is never reset on release or takeover.
     * Missing/expired/foreign ownership returns null; callers must fail closed.
     */
    Long currentEpoch(@Param("instanceId") Long instanceId, @Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken);

    int release(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("ownerToken") String ownerToken);
}
