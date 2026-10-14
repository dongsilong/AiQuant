package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import java.time.LocalDateTime;

/**
 * 幂等执行账本：状态迁移必须使用单条条件 UPDATE，而不是先读后无条件 updateById。
 * 交易事实 RECOVERED/CONFIRMED 不允许被迟到的 timeout/失败覆盖。
 */
@Mapper
public interface QuantExecutionRecordMapper extends BaseMapperPlus<QuantExecutionRecord, QuantExecutionRecord> {
    @Options(timeout = 3)
    @Update("""
        UPDATE quant_execution_record
        SET execution_status='CONFIRMED', exchange_order_id=COALESCE(#{orderId},exchange_order_id),
            exchange_order_status=#{exchangeStatus}, updated_at=#{now}
        WHERE id=#{id} AND execution_status IN ('PENDING','UNKNOWN')
        """)
    int markAccepted(@Param("id") Long id, @Param("orderId") Long orderId,
                     @Param("exchangeStatus") String exchangeStatus, @Param("now") LocalDateTime now);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_execution_record
        SET execution_status='FAILED', error_message=#{message}, updated_at=#{now}
        WHERE id=#{id} AND execution_status='PENDING'
        """)
    int markFailedBeforeSend(@Param("id") Long id, @Param("message") String message,
                             @Param("now") LocalDateTime now);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_execution_record
        SET execution_status='UNKNOWN', error_message=#{message}, updated_at=#{now}
        WHERE id=#{id} AND execution_status IN ('PENDING','UNKNOWN')
        """)
    int markUnknownIfUnresolved(@Param("id") Long id, @Param("message") String message,
                                 @Param("now") LocalDateTime now);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_execution_record
        SET execution_status='RECOVERED', exchange_order_id=COALESCE(#{orderId},exchange_order_id),
            exchange_order_status=COALESCE(#{exchangeStatus},exchange_order_status), updated_at=#{now}
        WHERE id=#{id} AND execution_status IN ('PENDING','UNKNOWN','CONFIRMED')
        """)
    int markRecoveredAfterFact(@Param("id") Long id, @Param("orderId") Long orderId,
                               @Param("exchangeStatus") String exchangeStatus,
                               @Param("now") LocalDateTime now);

    /**
     * A CANCEL may race with a fill. Annotate only the exact recovered exchange fact;
     * never update a stale entity with updateById after another recovery worker ran.
     */
    @Options(timeout = 3)
    @Update("""
        UPDATE quant_execution_record
        SET error_message=#{message}, updated_at=#{now}
        WHERE id=#{id} AND operation_type='CANCEL'
          AND execution_status='RECOVERED' AND exchange_order_status=#{exchangeStatus}
          AND exchange_order_id=#{orderId}
        """)
    int annotateRecoveredCancelFill(@Param("id") Long id, @Param("orderId") Long orderId,
                                    @Param("exchangeStatus") String exchangeStatus,
                                    @Param("message") String message, @Param("now") LocalDateTime now);

    @Options(timeout = 3)
    @Update("""
        UPDATE quant_execution_record
        SET recovery_attempts=COALESCE(recovery_attempts,0)+1,
            last_recovery_at=#{now}, updated_at=#{now}
        WHERE id=#{id} AND execution_status IN ('PENDING','UNKNOWN','CONFIRMED')
        """)
    int recordRecoveryAttempt(@Param("id") Long id, @Param("now") LocalDateTime now);
}
