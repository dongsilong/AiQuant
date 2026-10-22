package org.dromara.quant.risk.mapper;

import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 账户开仓额度的数据库访问接口。
 * 准入的账户行锁、读取当前占位、额度插入必须在同一个 REQUIRES_NEW 短事务内。
 * 不得把此处的单条原子 SQL 改成 BaseMapper 的先读后写。
 */
@Mapper
public interface OpenCapacityMapper {

    List<Long> lockCredential(@Param("credentialId") Long credentialId);

    List<Map<String, Object>> riskConfigs(@Param("credentialId") Long credentialId);

    List<Integer> strategyLimits(@Param("strategyId") Long strategyId);

    List<Map<String, Object>> activePositions(@Param("credentialId") Long credentialId);

    List<Map<String, Object>> activeStrategyStates(@Param("credentialId") Long credentialId);

    List<Map<String, Object>> reservedSlots(@Param("credentialId") Long credentialId);

    int release(@Param("credentialId") Long credentialId, @Param("key") String key);

    int insertReservation(@Param("credentialId") Long credentialId, @Param("key") String key, @Param("strategyId") Long strategyId, @Param("instanceId") Long instanceId, @Param(
        "symbol") String symbol, @Param("direction") String direction, @Param("margin") BigDecimal margin);

    Long countActiveLocalPositions(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    List<String> closedKeys(@Param("credentialId") Long credentialId, @Param("instanceId") Long instanceId, @Param("symbol") String symbol);

    List<String> balanceCoverage(@Param("credentialId") Long credentialId);

    List<Map<String, Object>> marginReservations(@Param("credentialId") Long credentialId);

    int releaseFailed(@Param("credentialId") Long credentialId, @Param("key") String key);
}
