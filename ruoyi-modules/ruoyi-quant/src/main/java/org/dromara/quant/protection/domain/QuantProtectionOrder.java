package org.dromara.quant.protection.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 持仓保护单本地账本。
 */
@Data
@TableName("quant_protection_order")
public class QuantProtectionOrder {
    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 交易凭证主键。
     */
    private Long credentialId;
    /** 创建保护意图时冻结的策略来源；未知的历史来源保持为空。 */
    private Long strategyId;
    /** 创建保护意图时冻结的策略实例，不能按当前交易对反推旧周期归属。 */
    private Long strategyInstanceId;
    /**
     * 本地持仓主键。
     */
    private Long positionId;
    /**
     * 所属持仓周期，防止同一持仓行复用时认领旧订单。
     */
    private String positionCycleKey;
    /**
     * 所属保护目标版本。
     */
    private Long targetVersion;
    /** 本地保护意图首次持久化时间，Unix epoch 毫秒；历史数据为空时不得推断订单不存在。 */
    @TableField("created_at_epoch_ms")
    private Long createdAtEpochMillis;
    /**
     * 保护意图覆盖的最高台阶，非阶梯保护为0。
     */
    private Integer stepIndex;
    /**
     * 该条件单已精确确认的累计成交量，不因重复查询累加。
     */
    private BigDecimal executedQuantity;
    /**
     * 交易对，例如 BTCUSDT。
     */
    private String symbol;
    /**
     * 持仓方向，例如 LONG/SHORT/BOTH。
     */
    private String positionSide;
    /**
     * 交易所订单主键。
     */
    private Long exchangeOrderId;
    /**
     * 客户端订单号。
     */
    private String clientOrderId;
    /**
     * 保护类型，例如 HARD_STOP、LOCAL_STOP、FIRST_TP、STEP_TP。
     */
    private String protectionType;
    /**
     * 订单类型。
     */
    private String orderType;
    /**
     * 条件单触发价格类型；历史记录为空时按 CONTRACT_PRICE 兼容恢复。
     */
    private String workingType;
    /**
     * 数量。
     */
    private BigDecimal quantity;
    /**
     * 条件单触发价格。
     */
    private BigDecimal stopPrice;
    /**
     * 当前状态。
     */
    private String status;
    /** Binance Algo 实际触发时间，Unix epoch 毫秒。 */
    @TableField("trigger_time_epoch_ms")
    private Long triggerTimeEpochMillis;
    /** 触发时间之前最近一次有效标记价格样本。 */
    @TableField("trigger_mark_price")
    private BigDecimal triggerMarkPrice;
    @TableField("trigger_mark_price_at_epoch_ms")
    private Long triggerMarkPriceAtEpochMillis;
    /** 触发时间之前最近一次有效合约 aggTrade 成交价样本，不代表盘口买卖价。 */
    @TableField("trigger_contract_trade_price")
    private BigDecimal triggerContractTradePrice;
    @TableField("trigger_contract_trade_at_epoch_ms")
    private Long triggerContractTradeAtEpochMillis;
    /** Binance 实际子市价单创建时间，Unix epoch 毫秒。 */
    @TableField("child_created_at_epoch_ms")
    private Long childCreatedAtEpochMillis;
    /** Algo 触发至子市价单创建的耗时，单位毫秒。 */
    @TableField("trigger_to_child_delay_ms")
    private Long triggerToChildDelayMillis;
    /** 实际子市价单成交均价。 */
    @TableField("actual_fill_price")
    private BigDecimal actualFillPrice;
    /** 相对保护触发价的方向性不利滑点百分比；正数为不利，负数为有利。 */
    @TableField("adverse_slippage_pct")
    private BigDecimal adverseSlippagePct;
}
