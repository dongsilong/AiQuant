package org.dromara.quant.strategy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略在账户 + 交易对上的运行实例。
 */
@Data
@TableName("quant_strategy_instance")
public class QuantStrategyInstance {
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
     * 策略交易对配置主键。
     */
    private Long strategySymbolId;
    /**
     * 交易凭证主键。
     */
    private Long credentialId;
    /**
     * 当前状态。
     */
    private String status;
    /**
     * 最近一次产生策略信号的时间。
     */
    private LocalDateTime lastSignalAt;
    /**
     * 最近一次运行错误信息。
     */
    private String lastError;
}
