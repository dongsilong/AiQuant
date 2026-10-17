package org.dromara.quant.strategy.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 策略的登录用户授权关系。
 */
@Data
@TableName("quant_strategy_user")
public class QuantStrategyUser {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long strategyId;
    private Long userId;
    private String status;
}
