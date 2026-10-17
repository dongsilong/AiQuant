package org.dromara.quant.account.domain.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.quant.account.domain.QuantTrade;

/**
 * 只读关联订单和执行来源后的成交事实。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TradeHistoryFill extends QuantTrade {
    private String positionSide;
    private Boolean reduceOnly;
    private String operationType;
}
