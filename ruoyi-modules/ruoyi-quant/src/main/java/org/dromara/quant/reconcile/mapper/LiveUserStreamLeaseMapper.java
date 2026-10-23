package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.*;

/**
 * Account-level database lease. DB time is authoritative, and expired ownership
 * can be taken over by another application node.
 */
@Mapper
public interface LiveUserStreamLeaseMapper {

    int ensure(@Param("id") Long id, @Param("owner") String owner, @Param("seconds") int seconds);

    int acquireOrRenew(@Param("id") Long id, @Param("owner") String owner, @Param("seconds") int seconds);

    Long currentEpoch(@Param("id") Long id, @Param("owner") String owner);

    /**
     * One DB-side snapshot for diagnosing a failed compare-and-set lease acquire.
     * Uses the exact same MyBatis datasource and database clock as acquireOrRenew.
     */

    String describe(@Param("id") Long id);

    long owns(@Param("id") Long id, @Param("owner") String owner);

    long ownsEpoch(@Param("id") Long id, @Param("owner") String owner, @Param("epoch") long epoch);

    int release(@Param("id") Long id, @Param("owner") String owner);
}
