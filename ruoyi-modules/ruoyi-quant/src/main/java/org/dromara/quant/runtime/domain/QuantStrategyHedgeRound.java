package org.dromara.quant.runtime.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 双开策略一轮 LONG/SHORT 入场的协调事实。
 * 持仓腿的成交、持仓和退出状态仍由 quant_strategy_position_state 管理。
 */
@Data
@TableName("quant_strategy_hedge_round")
public class QuantStrategyHedgeRound {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private String hedgeRoundKey;
    private Long strategyId;
    private Long instanceId;
    private Long credentialId;
    private String symbol;
    private LocalDateTime entryKlineOpenTime;
    private String status;
    private BigDecimal entryAngleDegrees;
    private BigDecimal entryEquitySnapshot;
    private BigDecimal marginRatio;
    private Integer leverage;
    private BigDecimal targetMarginPerLeg;
    private BigDecimal targetNotionalPerLeg;
    private String strategyParamsSnapshot;
    private String longLegStatus;
    private String shortLegStatus;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String longIdempotencyKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String shortIdempotencyKey;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String longClientOrderId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String shortClientOrderId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long longOrderId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long shortOrderId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long longPositionId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long shortPositionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @Version
    private Integer version;
}
