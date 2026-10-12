package org.dromara.quant.strategy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 策略交易对配置。
 */
@Data
@TableName("quant_strategy_symbol")
public class QuantStrategySymbol {
    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 策略定义主键；为空表示非策略来源。
     */
    private Long strategyId;
    /**
     * 交易对主键。
     */
    private Long symbolId;
    /**
     * K 线周期编码。
     */
    private String intervalCode;
    /**
     * 当前交易对开仓资金比例。
     */
    private BigDecimal openRatio;
    /**
     * 固定硬止损回报率；KlineSlopeRateStrategy 不从 params 读取该值。
     */
    private BigDecimal stopLossRatio;
    /**
     * Binance 交易所固定硬止损：按初始保证金亏损比例计算。
     */
    private BigDecimal hardStopMarginLossRatio;
    /**
     * 杠杆倍数。
     */
    private Integer leverage;
    /**
     * 交易对级保证金模式覆盖值。
     */
    private String marginMode;
    /**
     * 策略参数 JSON；保存算法参数，不保存固定硬止损字段。
     */
    private String paramsJson;
    /**
     * 当前状态。
     */
    private String status;
}
