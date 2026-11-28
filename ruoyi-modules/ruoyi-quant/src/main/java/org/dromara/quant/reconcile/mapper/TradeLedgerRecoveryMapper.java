package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Coordinates one-time account-level recovery when the local trade ledger has been emptied. */
@Mapper
public interface TradeLedgerRecoveryMapper {
    String selectStatus(@Param("credentialId") Long credentialId);

    int insertReady(@Param("credentialId") Long credentialId);

    /** Claims an absent/expired recovery lease; completed or actively running recoveries are not claimed. */
    int claim(@Param("credentialId") Long credentialId);

    int complete(@Param("credentialId") Long credentialId);

    int releaseForRetry(@Param("credentialId") Long credentialId);
}
