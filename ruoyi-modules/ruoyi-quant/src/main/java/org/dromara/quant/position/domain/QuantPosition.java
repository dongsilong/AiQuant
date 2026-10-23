package org.dromara.quant.position.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 本地持仓账本。
 */
@Data
@TableName("quant_position")
public class QuantPosition {
    /**
     * 运行时保护协调标识，不写入持仓事实表。
     */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String protectionCycleKey;

    /** 从策略冻结快照传入保护服务的内部规则版本，不写入持仓事实表。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Integer protectionPolicyVersion;

    /** V2 周期已持久化的固定硬止损价格，只用于运行时保护同步。 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private BigDecimal protectionStopLossPrice;

    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 交易凭证主键。
     */
    private Long credentialId;
    /**
     * 交易对，例如 BTCUSDT。
     */
    private String symbol;
    /**
     * 持仓方向，例如 LONG/SHORT/BOTH。
     */
    private String positionSide;
    /**
     * 数量。
     */
    private BigDecimal quantity;
    /**
     * 平均开仓价。
     */
    private BigDecimal entryPrice;
    /**
     * 交易所标记价格。
     */
    private BigDecimal markPrice;
    /**
     * 未实现盈亏。
     */
    private BigDecimal unrealizedPnl;
    /**
     * 预估强平价格。
     */
    private BigDecimal liquidationPrice;
    /**
     * 杠杆倍数。
     */
    private Integer leverage;
    /**
     * 持仓占用保证金。
     */
    private BigDecimal margin;
    /**
     * 最后更新时间。
     */
    private LocalDateTime updatedAt;
}
