package org.dromara.quant.config.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.format.DateTimeFormat;
import org.dromara.quant.config.domain.QuantStrategy;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略定义。
 */
@Data
@ExcelIgnoreUnannotated
@AutoMapper(target = QuantStrategy.class)
public class QuantStrategyVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     */
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
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    /**
     * 最后更新时间。
     */
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
