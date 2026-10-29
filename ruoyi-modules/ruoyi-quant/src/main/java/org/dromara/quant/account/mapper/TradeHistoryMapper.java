package org.dromara.quant.account.mapper;

import org.apache.ibatis.annotations.Param;
import org.dromara.quant.account.domain.vo.TradeHistoryFill;

import java.util.List;

/**
 * 客户隔离后的只读成交周期查询，不调用交易所。
 */
public interface TradeHistoryMapper {
    // 先限制成交窗口，再关联来源；保留20001哨兵行和重复来源，让服务拒绝截断或歧义账本。

    List<TradeHistoryFill> selectFills(@Param("ids") List<Long> ids, @Param("symbol") String symbol);

    List<Long> selectOrderIdsMissingOrderFacts(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("limit") int limit);
}
