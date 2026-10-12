package org.dromara.quant.account.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.quant.account.domain.vo.TradeHistoryFill;
import java.util.List;

/** 客户隔离后的只读成交周期查询，不调用交易所。 */
public interface TradeHistoryMapper {
    // 先限制成交窗口，再关联来源；保留20001哨兵行和重复来源，让服务拒绝截断或歧义账本。
    @Select("""
        <script>
        SELECT t.*, o.position_side, o.reduce_only, e.operation_type
        FROM (
            SELECT * FROM quant_trade
            WHERE credential_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            <if test="symbol != null">AND symbol = #{symbol}</if>
            ORDER BY trade_time, id
            LIMIT 20001
        ) t
        LEFT JOIN quant_order o ON o.credential_id = t.credential_id
            AND o.symbol = t.symbol AND o.exchange_order_id = t.exchange_order_id
        LEFT JOIN quant_execution_record e ON e.credential_id = t.credential_id
            AND e.symbol = t.symbol AND e.exchange_order_id = t.exchange_order_id
            AND e.operation_type IN ('OPEN', 'CLOSE')
        ORDER BY t.trade_time, t.id
        LIMIT 20001
        </script>
        """)
    List<TradeHistoryFill> selectFills(@Param("ids") List<Long> ids, @Param("symbol") String symbol);
}
