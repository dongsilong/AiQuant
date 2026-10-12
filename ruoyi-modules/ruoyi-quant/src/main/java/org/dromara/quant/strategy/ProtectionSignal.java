package org.dromara.quant.strategy;

import java.math.BigDecimal;

/**
 * 策略计算出的交易所侧保护意图。Runtime/ProtectionOrderService 负责真正挂单、撤单和恢复。
 *
 * @param type         信号、事件或订单类型
 * @param orderType    订单类型
 * @param quantityRate 相对基准仓位的数量比例
 * @param quantityBase 比例数量基准：CURRENT 为当前仓位，INITIAL 为初始仓位
 * @param triggerPrice 条件单触发价格
 * @param reason       信号触发原因
 */
public record ProtectionSignal(String type, String orderType, BigDecimal quantityRate, QuantityBase quantityBase, BigDecimal triggerPrice, String reason, int stepIndex) {

    /** 兼容非阶梯保护信号。 */
    public ProtectionSignal(String type, String orderType, BigDecimal quantityRate, QuantityBase quantityBase, BigDecimal triggerPrice, String reason) {
        this(type, orderType, quantityRate, quantityBase, triggerPrice, reason, 0);
    }

    /**
     * QuantityBase，量化交易模块领域/基础设施对象。
     */
    public enum QuantityBase {
        CURRENT, INITIAL
    }
}
