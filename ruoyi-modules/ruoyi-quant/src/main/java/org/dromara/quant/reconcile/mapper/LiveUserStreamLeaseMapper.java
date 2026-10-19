package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.*;

/**
 * Account-level database lease. DB time is authoritative, and expired ownership
 * can be taken over by another application node.
 */
@Mapper
public interface LiveUserStreamLeaseMapper {
    @Options(timeout = 3)
    @Insert("""
        INSERT IGNORE INTO quant_live_user_stream_lease
          (credential_id, owner_token, lease_epoch, lease_until)
        VALUES (#{id}, #{owner}, 1, DATE_ADD(NOW(6), INTERVAL #{seconds} SECOND))
        """)
    int ensure(@Param("id") Long id, @Param("owner") String owner, @Param("seconds") int seconds);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_user_stream_lease
        SET lease_epoch=IF(owner_token<>#{owner} OR lease_until<=NOW(6), lease_epoch+1, lease_epoch),
            owner_token=#{owner}, lease_until=DATE_ADD(NOW(6), INTERVAL #{seconds} SECOND)
        WHERE credential_id=#{id}
          AND (owner_token=#{owner} OR lease_until<=NOW(6))
        """)
    int acquireOrRenew(@Param("id") Long id, @Param("owner") String owner, @Param("seconds") int seconds);

    @Options(timeout = 3)
    @Select("""
        SELECT lease_epoch FROM quant_live_user_stream_lease
        WHERE credential_id=#{id} AND owner_token=#{owner} AND lease_until>NOW(6)
        """)
    Long currentEpoch(@Param("id") Long id, @Param("owner") String owner);

    /**
     * One DB-side snapshot for diagnosing a failed compare-and-set lease acquire.
     * Uses the exact same MyBatis datasource and database clock as acquireOrRenew.
     */
    @Options(timeout = 3)
    @Select("""
        SELECT CONCAT(
            'schema=', DATABASE(),
            ', owner=', owner_token,
            ', epoch=', lease_epoch,
            ', lease_until=', DATE_FORMAT(lease_until, '%Y-%m-%d %H:%i:%s.%f'),
            ', db_now=', DATE_FORMAT(NOW(6), '%Y-%m-%d %H:%i:%s.%f'),
            ', remaining_ms=', TIMESTAMPDIFF(MICROSECOND, NOW(6), lease_until) DIV 1000
        )
        FROM quant_live_user_stream_lease WHERE credential_id=#{id}
        """)
    String describe(@Param("id") Long id);

    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_live_user_stream_lease
        WHERE credential_id=#{id} AND owner_token=#{owner} AND lease_until>NOW(6)
        """)
    long owns(@Param("id") Long id, @Param("owner") String owner);

    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_live_user_stream_lease
        WHERE credential_id=#{id} AND owner_token=#{owner} AND lease_epoch=#{epoch} AND lease_until>NOW(6)
        """)
    long ownsEpoch(@Param("id") Long id, @Param("owner") String owner, @Param("epoch") long epoch);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_user_stream_lease SET lease_until=NOW(6)
        WHERE credential_id=#{id} AND owner_token=#{owner} AND lease_until>NOW(6)
        """)
    int release(@Param("id") Long id, @Param("owner") String owner);
}
