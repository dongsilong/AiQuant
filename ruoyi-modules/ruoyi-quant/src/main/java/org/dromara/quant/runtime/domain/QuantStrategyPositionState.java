package org.dromara.quant.runtime.domain;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 策略持仓运行状态事实表。用于重启恢复，不以策略日志代替运行状态。
 */
@Data
@TableName("quant_strategy_position_state")
public class QuantStrategyPositionState {
    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 策略运行实例主键。
     */
    private Long instanceId;
    /**
     * 本地持仓主键。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long positionId;
    /**
     * 持仓周期标识；旧持仓为空时不得猜测恢复。
     */
    private String positionCycleKey;
    /**
     * 开仓实际成交时冻结的有效杠杆。
     */
    private Integer frozenLeverage;
    /**
     * 开仓冻结的交易对绑定止损比例，未配置时为空。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal frozenSymbolStopLossRatio;
    /**
     * 规范化后的首次止盈目标数量。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal firstTakeProfitTargetQty;
    /**
     * 本周期首次止盈已确认累计成交数量（本地及交易所）。
     */
    private BigDecimal firstTakeProfitExecutedQty;
    /**
     * 本地首次止盈已确认成交量；与条件单成交分开计量防止重复累计。
     */
    private BigDecimal firstTakeProfitLocalExecutedQty;
    /**
     * 当前状态。
     */
    private String status;
    /**
     * 策略持仓方向。
     */
    private String direction;
    /**
     * 平均开仓价。
     */
    private BigDecimal entryPrice;
    /**
     * 开仓时 ATR 快照。
     */
    private BigDecimal entryAtr;
    /**
     * 当前策略持仓数量。
     */
    private BigDecimal positionQty;
    /**
     * 首次建仓时的初始总数量。
     */
    private BigDecimal initialPositionQty;
    /**
     * 开仓时初始保证金。
     */
    private BigDecimal initialMargin;
    /**
     * 开仓时冻结的硬止损比例。
     */
    private BigDecimal frozenStopLossRatio;
    /**
     * 开仓时冻结的策略参数 JSON。
     */
    private String strategyParamsSnapshot;
    /**
     * 开仓后的最高实时价。
     */
    private BigDecimal highestPriceSinceEntry;
    /**
     * 开仓后的最低实时价。
     */
    private BigDecimal lowestPriceSinceEntry;
    /** 持仓周期内按实时标记价回报率采样得到的最大值。 */
    @TableField(updateStrategy = FieldStrategy.NEVER)
    private BigDecimal maximumReturnRate;
    /**
     * 当前已达到的最高收益阶梯。
     */
    private Integer highestStep;
    /**
     * 已经精确确认生效的阶梯保护台阶，提交未知时不推进。
     */
    private Integer confirmedProtectionStep;
    /**
     * 首次分批止盈是否已经确认完成。
     */
    private Boolean firstTakeProfitTriggered;
    /**
     * 当前策略记录的止损触发价。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal stopLossPrice;
    /**
     * 等待交易所确认或恢复的动作类型。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pendingAction;
    /**
     * 待确认动作对应的执行幂等键。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pendingIdempotencyKey;
    /**
     * 待确认动作对应的客户端订单号。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String pendingClientOrderId;
    /**
     * 待确认动作对应的交易所订单号。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long pendingOrderId;
    /**
     * 待确认动作发起时间。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long pendingOrderTime;
    /**
     * 是否需要进入恢复流程。
     */
    private Boolean recoveryRequired;
    /**
     * 恢复状态是否不安全；为 true 时禁止继续自动交易。
     */
    private Boolean unsafeRecovery;
    /**
     * 待确认订单连续未查询到的确认次数。
     */
    private Integer pendingNotFoundConfirmations;
    /**
     * 策略持仓建立时间。
     */
    private LocalDateTime openedAt;
    /**
     * 最后更新时间。
     */
    private LocalDateTime updatedAt;
    /**
     * 乐观锁版本号。
     */
    @Version
    private Integer version;
}
