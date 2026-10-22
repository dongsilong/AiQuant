package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.execution.domain.QuantExecutionRecord;

import java.time.LocalDateTime;

/**
 * 幂等执行账本：状态迁移必须使用单条条件 UPDATE，而不是先读后无条件 updateById。
 * 交易事实 RECOVERED/CONFIRMED 不允许被迟到的 timeout/失败覆盖。
 */
@Mapper
public interface QuantExecutionRecordMapper extends BaseMapperPlus<QuantExecutionRecord, QuantExecutionRecord> {

    int markAccepted(@Param("id") Long id, @Param("orderId") Long orderId, @Param("exchangeStatus") String exchangeStatus, @Param("now") LocalDateTime now);

    int markFailedBeforeSend(@Param("id") Long id, @Param("message") String message, @Param("now") LocalDateTime now);

    int markUnknownIfUnresolved(@Param("id") Long id, @Param("message") String message, @Param("now") LocalDateTime now);

    int markRecoveredAfterFact(@Param("id") Long id, @Param("orderId") Long orderId, @Param("exchangeStatus") String exchangeStatus, @Param("now") LocalDateTime now);

    /**
     * A CANCEL may race with a fill. Annotate only the exact recovered exchange fact;
     * never update a stale entity with updateById after another recovery worker ran.
     */

    int annotateRecoveredCancelFill(@Param("id") Long id, @Param("orderId") Long orderId, @Param("exchangeStatus") String exchangeStatus, @Param("message") String message,
                                    @Param("now") LocalDateTime now);

    /**
     * Crash-recovery gap: the execution record may already be RECOVERED while
     * its durable account-symbol dispatch guard remains held. This query only
     * selects exact matching active tickets, NOT all historical recovered orders.
     */
    java.util.List<QuantExecutionRecord> strandedRecoveredDispatches();

    int recordRecoveryAttempt(@Param("id") Long id, @Param("now") LocalDateTime now);
}
