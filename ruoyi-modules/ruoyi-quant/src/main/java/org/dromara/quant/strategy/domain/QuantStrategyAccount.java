package org.dromara.quant.strategy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 策略与交易账户关联。
 */
@Data
@TableName("quant_strategy_account")
public class QuantStrategyAccount {
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
     * 交易凭证主键。
     */
    private Long credentialId;
    /**
     * 当前状态。
     */
    private String status;
}
