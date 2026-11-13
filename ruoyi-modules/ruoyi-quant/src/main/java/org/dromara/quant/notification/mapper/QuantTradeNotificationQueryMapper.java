package org.dromara.quant.notification.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 交易通知相关的跨表查询及快照 SQL。
 * 单表实体 CRUD 仍使用对应的 MyBatis-Plus BaseMapper。
 */
@Mapper
public interface QuantTradeNotificationQueryMapper {

    @Select("""
        SELECT DISTINCT ur.user_id
        FROM sys_user_role ur
        JOIN sys_role r ON r.role_id = ur.role_id
        JOIN sys_user u ON u.user_id = ur.user_id
        WHERE r.role_key = 'admin' AND u.status = '0' AND u.del_flag = '0'
        """)
    List<Long> selectActiveAdminUserIds();

    @Update("""
        UPDATE quant_trade_close_equity
        SET closing_equity = #{closingEquity}, wallet_balance = #{walletBalance},
            unrealized_pnl = #{unrealizedPnl}, capture_source = #{captureSource},
            close_event_time = #{closeEventTime},
            account_update_transaction_time = #{accountUpdateTransactionTime},
            exchange_event_time = #{exchangeEventTime}, stream_received_at = #{streamReceivedAt},
            rest_query_started_at = #{restRequestTime},
            rest_response_received_at = #{restResponseTime}, captured_at = UTC_TIMESTAMP(3)
        WHERE credential_id = #{credentialId} AND symbol = #{symbol}
          AND exchange_order_id = #{orderId}
          AND (closing_equity IS NULL OR wallet_balance IS NULL)
        """)
    int updateIncompleteCloseEquity(@Param("credentialId") Long credentialId,
                                    @Param("symbol") String symbol,
                                    @Param("orderId") Long orderId,
                                    @Param("closingEquity") java.math.BigDecimal closingEquity,
                                    @Param("walletBalance") java.math.BigDecimal walletBalance,
                                    @Param("unrealizedPnl") java.math.BigDecimal unrealizedPnl,
                                    @Param("captureSource") String captureSource,
                                    @Param("closeEventTime") LocalDateTime closeEventTime,
                                    @Param("accountUpdateTransactionTime") LocalDateTime accountUpdateTransactionTime,
                                    @Param("exchangeEventTime") LocalDateTime exchangeEventTime,
                                    @Param("streamReceivedAt") LocalDateTime streamReceivedAt,
                                    @Param("restRequestTime") LocalDateTime restRequestTime,
                                    @Param("restResponseTime") LocalDateTime restResponseTime);

    @Insert("""
        INSERT INTO quant_trade_close_equity
            (credential_id, symbol, exchange_order_id, closing_equity, wallet_balance,
             unrealized_pnl, capture_source, close_event_time,
             account_update_transaction_time, exchange_event_time, stream_received_at,
             rest_query_started_at, rest_response_received_at, captured_at)
        VALUES
            (#{credentialId}, #{symbol}, #{orderId}, #{closingEquity}, #{walletBalance},
             #{unrealizedPnl}, #{captureSource}, #{closeEventTime},
             #{accountUpdateTransactionTime}, #{exchangeEventTime}, #{streamReceivedAt},
             #{restRequestTime}, #{restResponseTime}, UTC_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE id = id
        """)
    int insertCloseEquityIfAbsent(@Param("credentialId") Long credentialId,
                                  @Param("symbol") String symbol,
                                  @Param("orderId") Long orderId,
                                  @Param("closingEquity") java.math.BigDecimal closingEquity,
                                  @Param("walletBalance") java.math.BigDecimal walletBalance,
                                  @Param("unrealizedPnl") java.math.BigDecimal unrealizedPnl,
                                  @Param("captureSource") String captureSource,
                                  @Param("closeEventTime") LocalDateTime closeEventTime,
                                  @Param("accountUpdateTransactionTime") LocalDateTime accountUpdateTransactionTime,
                                  @Param("exchangeEventTime") LocalDateTime exchangeEventTime,
                                  @Param("streamReceivedAt") LocalDateTime streamReceivedAt,
                                  @Param("restRequestTime") LocalDateTime restRequestTime,
                                  @Param("restResponseTime") LocalDateTime restResponseTime);

    @Select("""
        SELECT c.credential_id, c.symbol, f.exchange_order_id,
               DATE_FORMAT(f.trade_time, '%Y-%m-%dT%H:%i:%s.%f') AS close_time_utc
        FROM quant_trade_cycle c
        JOIN quant_trade_cycle_fill f
          ON f.cycle_id = c.id AND f.fill_role = 'CLOSE'
        WHERE c.credential_id = #{credentialId}
          AND c.status = 'CLOSED'
          AND c.closed_at >= UTC_TIMESTAMP(3) - INTERVAL 2 MINUTE
          AND NOT EXISTS (
              SELECT 1 FROM quant_trade_cycle_fill newer
              WHERE newer.cycle_id = f.cycle_id AND newer.fill_role = 'CLOSE'
                AND (newer.trade_time > f.trade_time
                     OR (newer.trade_time = f.trade_time AND newer.id > f.id))
          )
          AND NOT EXISTS (
              SELECT 1 FROM quant_trade_close_equity s
              WHERE s.credential_id = c.credential_id AND s.symbol = c.symbol
                AND s.exchange_order_id = f.exchange_order_id
                AND s.closing_equity IS NOT NULL AND s.wallet_balance IS NOT NULL
          )
          AND NOT EXISTS (
              SELECT 1 FROM quant_notification n
              WHERE n.credential_id = c.credential_id AND n.channel = 'FACT_PENDING'
                AND n.recipient = CONCAT('SNAPSHOT:', c.symbol, ':', f.exchange_order_id)
          )
        ORDER BY c.closed_at ASC
        LIMIT 50
        """)
    List<Map<String, Object>> selectRecentCloseSnapshotCandidates(@Param("credentialId") Long credentialId);

    @Select("""
        SELECT EXISTS (
            SELECT 1 FROM quant_strategy_order_intent
            WHERE credential_id = #{credentialId} AND symbol = #{symbol}
              AND exchange_order_id = #{orderId} AND fill_role = 'CLOSE'
            UNION ALL
            SELECT 1 FROM quant_execution_record
            WHERE credential_id = #{credentialId} AND symbol = #{symbol}
              AND exchange_order_id = #{orderId} AND operation_type = 'CLOSE'
            UNION ALL
            SELECT 1 FROM quant_strategy_pre_dispatch_intent
            WHERE credential_id = #{credentialId} AND symbol = #{symbol}
              AND client_order_id = #{clientOrderId} AND operation_type = 'CLOSE'
            UNION ALL
            SELECT 1 FROM quant_trade_cycle_fill
            WHERE credential_id = #{credentialId} AND symbol = #{symbol}
              AND exchange_order_id = #{orderId} AND fill_role = 'CLOSE'
        )
        """)
    Integer existsVerifiedCloseOrder(@Param("credentialId") Long credentialId,
                                     @Param("symbol") String symbol,
                                     @Param("orderId") Long orderId,
                                     @Param("clientOrderId") String clientOrderId);

    @Select("""
        SELECT EXISTS (
            SELECT 1 FROM quant_trade_close_equity
            WHERE credential_id = #{credentialId} AND symbol = #{symbol}
              AND exchange_order_id = #{orderId}
              AND closing_equity IS NOT NULL AND wallet_balance IS NOT NULL
        )
        """)
    Integer existsCompleteCloseEquity(@Param("credentialId") Long credentialId,
                                      @Param("symbol") String symbol,
                                      @Param("orderId") Long orderId);

    @Update("UPDATE quant_notification SET last_error = NULL WHERE id = #{notificationId}")
    int clearNotificationLastError(@Param("notificationId") Long notificationId);
}
