package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

/**
 * Short-lived InnoDB row lock plus persisted request-start watermark.
 */
@Mapper
public interface LivePositionSnapshotFenceMapper {

    int ensure(@Param("id") Long credentialId);

    LocalDateTime lockAndRead(@Param("id") Long credentialId);

    int advance(@Param("id") Long credentialId, @Param("started") LocalDateTime started);
}
