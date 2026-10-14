package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
    int ensure(@Param("id") Long id, @Param("owner") String owner,
               @Param("seconds") int seconds);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_user_stream_lease
        SET lease_epoch=IF(owner_token<>#{owner} OR lease_until<=NOW(6), lease_epoch+1, lease_epoch),
            owner_token=#{owner}, lease_until=DATE_ADD(NOW(6), INTERVAL #{seconds} SECOND)
        WHERE credential_id=#{id}
          AND (owner_token=#{owner} OR lease_until<=NOW(6))
        """)
    int acquireOrRenew(@Param("id") Long id, @Param("owner") String owner,
                       @Param("seconds") int seconds);

    @Options(timeout = 3)
    @Select("""
        SELECT COUNT(*) FROM quant_live_user_stream_lease
        WHERE credential_id=#{id} AND owner_token=#{owner} AND lease_until>NOW(6)
        """)
    long owns(@Param("id") Long id, @Param("owner") String owner);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_live_user_stream_lease SET lease_until=NOW(6)
        WHERE credential_id=#{id} AND owner_token=#{owner} AND lease_until>NOW(6)
        """)
    int release(@Param("id") Long id, @Param("owner") String owner);
}
