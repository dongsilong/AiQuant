package org.dromara.quant.config.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略定义。
 */
@Data
@TableName("quant_strategy")
public class QuantStrategy implements Serializable {

    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 策略显示名称。
     */
    private String strategyName;
    /**
     * 策略插件编码，用于定位 StrategyPlugin 实现。
     */
    private String pluginCode;
    /**
     * 策略插件版本。
     */
    private String pluginVersion;
    /**
     * 策略类型。
     */
    private String strategyType;
    /**
     * 策略允许的最大同时开仓数量。
     */
    private Integer maxOpenCount;
    /**
     * 策略默认 K 线周期。
     */
    private String defaultInterval;
    /**
     * 默认开仓资金比例。
     */
    private BigDecimal defaultOpenRatio;
    /**
     * 默认止损比例。
     */
    private BigDecimal defaultStopLossRatio;
    /**
     * 固定硬止损回报率；KlineSlopeRateStrategy 直接读取该字段，不从 params 读取。
     */
    private BigDecimal stopLossRatio;
    /**
     * Binance 交易所固定硬止损：按初始保证金亏损比例计算。
     */
    private BigDecimal hardStopMarginLossRatio;
    /**
     * 默认杠杆倍数。
     */
    private Integer defaultLeverage;
    /**
     * 默认保证金模式：ISOLATED 或 CROSSED。
     */
    private String defaultMarginMode;
    /**
     * 策略参数 JSON；保存算法参数，不保存固定硬止损字段。
     */
    private String paramsJson;
    /**
     * 当前状态。
     */
    private String status;
    /**
     * 创建时间。
     */
    private LocalDateTime createTime;
    /**
     * 最后更新时间。
     */
    private LocalDateTime updateTime;
}
