package org.dromara.quant.account.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.domain.vo.QuantTradeVo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * QuantTradeMapper，负责对应实体的 MyBatis-Plus 持久化操作。
 */
public interface QuantTradeMapper extends BaseMapperPlus<QuantTrade, QuantTradeVo> {
    /** 仅补齐精确成交身份上尚未归属的策略来源，不覆盖已有归属。 */
    @Update("""
        UPDATE quant_trade
        SET strategy_id = #{strategyId},
            strategy_instance_id = #{strategyInstanceId}
        WHERE credential_id = #{credentialId}
          AND symbol = #{symbol}
          AND exchange_trade_id = #{exchangeTradeId}
          AND exchange_order_id = #{exchangeOrderId}
          AND strategy_id IS NULL
          AND strategy_instance_id IS NULL
        """)
    int backfillStrategyAttribution(@Param("credentialId") Long credentialId, @Param("symbol") String symbol,
                                    @Param("exchangeTradeId") Long exchangeTradeId, @Param("exchangeOrderId") Long exchangeOrderId,
                                    @Param("strategyId") Long strategyId, @Param("strategyInstanceId") Long strategyInstanceId);
}
