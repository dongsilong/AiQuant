package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * MySQL 原子策略租约。唯一键 (credential_id, symbol) 是跨实例排他保证。
 * 超时仅作用于该 Mapper 方法，不修改共享 SqlSession / JdbcTemplate。
 */
@Mapper
public interface StrategyRuntimeLeaseMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT IGNORE INTO quant_strategy_runtime_lease
            (instance_id, credential_id, symbol, owner_token, lease_until, create_time, update_time)
        VALUES (#{instanceId}, #{credentialId}, #{symbol}, #{ownerToken},
                DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND), NOW(), NOW())
        """)
    int ensureRow(@Param("instanceId") Long instanceId,
                  @Param("credentialId") Long credentialId,
                  @Param("symbol") String symbol,
                  @Param("ownerToken") String ownerToken,
                  @Param("leaseSeconds") int leaseSeconds);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_strategy_runtime_lease
        SET instance_id=#{instanceId}, owner_token=#{ownerToken},
            lease_until=DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND),
            update_time=NOW()
        WHERE credential_id=#{credentialId} AND symbol=#{symbol}
          AND (owner_token=#{ownerToken} OR lease_until<NOW())
        """)
    int acquireOrRenew(@Param("instanceId") Long instanceId,
                       @Param("credentialId") Long credentialId,
                       @Param("symbol") String symbol,
                       @Param("ownerToken") String ownerToken,
                       @Param("leaseSeconds") int leaseSeconds);

    @Options(timeout = 3)
    @Delete("""
        DELETE FROM quant_strategy_runtime_lease
        WHERE credential_id=#{credentialId} AND symbol=#{symbol} AND owner_token=#{ownerToken}
        """)
    int release(@Param("credentialId") Long credentialId,
                @Param("symbol") String symbol,
                @Param("ownerToken") String ownerToken);
}
