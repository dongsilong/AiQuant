package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

/** Short-lived InnoDB row lock plus persisted request-start watermark. */
@Mapper
public interface LivePositionSnapshotFenceMapper {
    @Insert("INSERT IGNORE INTO quant_position_sync_fence (credential_id) VALUES (#{id})")
    int ensure(@Param("id") Long credentialId);

    @Select("SELECT snapshot_started_at FROM quant_position_sync_fence WHERE credential_id = #{id} FOR UPDATE")
    LocalDateTime lockAndRead(@Param("id") Long credentialId);

    @Update("UPDATE quant_position_sync_fence SET snapshot_started_at = #{started}, updated_at = NOW(6) WHERE credential_id = #{id}")
    int advance(@Param("id") Long credentialId, @Param("started") LocalDateTime started);
}
