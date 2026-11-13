package org.dromara.quant.decision.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.quant.decision.TradeDecisionQuery;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 决策日志读写 SQL。
 */
@Mapper
public interface TradeDecisionLogMapper {

    List<Map<String, Object>> selectByEventKey(@Param("eventKey") String eventKey);

    List<Map<String, Object>> selectCredentialInfo(@Param("credentialId") Long credentialId);

    List<Map<String, Object>> selectExecutionRecord(@Param("credentialId") Long credentialId,
                                                      @Param("symbol") String symbol,
                                                      @Param("orderId") Long orderId,
                                                      @Param("clientOrderId") String clientOrderId);

    List<Map<String, Object>> selectOrderForExecution(@Param("credentialId") Long credentialId,
                                                        @Param("symbol") String symbol,
                                                        @Param("orderId") Long orderId);

    List<Map<String, Object>> selectProtectionForOrder(@Param("credentialId") Long credentialId,
                                                        @Param("symbol") String symbol,
                                                        @Param("orderId") Long orderId,
                                                        @Param("clientOrderId") String clientOrderId);

    List<Map<String, Object>> selectProtectionForClientOrder(@Param("credentialId") Long credentialId,
                                                               @Param("symbol") String symbol,
                                                               @Param("clientOrderId") String clientOrderId);

    List<Map<String, Object>> selectStrategyName(@Param("strategyId") Long strategyId);

    List<Map<String, Object>> selectOrderStatus(@Param("credentialId") Long credentialId,
                                                 @Param("symbol") String symbol,
                                                 @Param("orderId") Long orderId);

    Map<String, Object> selectFillTotals(@Param("parentKey") String parentKey);

    int insertEvaluations(@Param("rows") List<Map<String, Object>> rows);

    int insertLog(@Param("row") Map<String, Object> row);

    int insertFill(@Param("row") Map<String, Object> row);

    int updateStrategyMetadata(@Param("strategyId") Long strategyId,
                               @Param("instanceId") Long instanceId,
                               @Param("strategyName") String strategyName,
                               @Param("operation") String operation,
                               @Param("eventKey") String eventKey);

    int updateDecisionLog(@Param("status") String status,
                          @Param("reason") String reason,
                          @Param("reasonCode") String reasonCode,
                          @Param("orderId") Long orderId,
                          @Param("clientOrderId") String clientOrderId,
                          @Param("quantity") BigDecimal quantity,
                          @Param("price") BigDecimal price,
                          @Param("tradeTime") Timestamp tradeTime,
                          @Param("direction") String direction,
                          @Param("margin") BigDecimal margin,
                          @Param("eventTime") Timestamp eventTime,
                          @Param("eventKey") String eventKey);

    int updateFillParent(@Param("target") String target, @Param("source") String source);

    int deleteSourceOrder(@Param("source") String source);

    int updateAggregatedFill(@Param("quantity") BigDecimal quantity,
                             @Param("price") BigDecimal price,
                             @Param("tradeTime") Timestamp tradeTime,
                             @Param("status") String status,
                             @Param("eventTime") Timestamp eventTime,
                             @Param("parentKey") String parentKey,
                             @Param("quantityLimit") BigDecimal quantityLimit);

    Long countLogs(@Param("q") TradeDecisionQuery query,
                   @Param("from") LocalDateTime from,
                   @Param("to") LocalDateTime to);

    List<Map<String, Object>> selectLogs(@Param("q") TradeDecisionQuery query,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to,
                                         @Param("limit") int limit,
                                         @Param("offset") long offset);

    List<Map<String, Object>> selectDetail(@Param("id") Long id);
}
