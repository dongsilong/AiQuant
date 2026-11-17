package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Account-symbol lease for historical Binance fill collection.
 * Database time and the fencing epoch are authoritative across application nodes.
 */
@Mapper
public interface TradeHistoryCollectionLeaseMapper {

    int ensure(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    int acquire(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                @Param("owner") String owner, @Param("seconds") int seconds);

    Long currentEpoch(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                      @Param("owner") String owner);

    int renew(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
              @Param("owner") String owner, @Param("epoch") long epoch, @Param("seconds") int seconds);

    Long lockOwned(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                   @Param("owner") String owner, @Param("epoch") long epoch);

    int release(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                @Param("owner") String owner, @Param("epoch") long epoch);
}
