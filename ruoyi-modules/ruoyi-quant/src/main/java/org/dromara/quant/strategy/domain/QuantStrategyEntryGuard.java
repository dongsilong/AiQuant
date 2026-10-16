package org.dromara.quant.strategy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 同一账户凭证 + 交易对 + K线开盘时间全局只允许领取一次开仓名额；instanceId 仅记录归属。
 */
@Data
@TableName("quant_strategy_entry_guard")
public class QuantStrategyEntryGuard {
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
     * 策略运行实例主键。
     */
    private Long instanceId;
    /**
     * klineOpenTime 对应时间。
     */
    private LocalDateTime klineOpenTime;
    /**
     * 交易所订单主键。
     */
    private Long orderId;
    /** ENTRY 开仓尝试，CLOSE 已发生持仓减少的 K 线封锁。 */
    private String guardType;
    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
}
