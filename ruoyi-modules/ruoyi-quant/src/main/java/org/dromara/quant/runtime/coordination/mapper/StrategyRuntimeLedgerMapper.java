package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

/**
 * Read-only cross-instance strategy exposure fact; keep SQL centralised outside runtime orchestration.
 */
@Mapper
public interface StrategyRuntimeLedgerMapper {

    Long countStrategyExposures(@Param("strategyId") Long strategyId, @Param("credentialId") Long credentialId);

    List<Map<String, Object>> findStrategyExposures(@Param("strategyId") Long strategyId, @Param("credentialId") Long credentialId);
}
