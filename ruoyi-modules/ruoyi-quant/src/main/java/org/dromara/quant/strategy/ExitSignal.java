package org.dromara.quant.strategy;

import java.math.BigDecimal;

/**
 * 策略退出信号。
 * markFirstTakeProfitTriggered 用于把“首次分批止盈已完成”这一通用运行状态交给 Runtime 持久化。
 * exchangeProtectionType 非空时表示该退出有等价 Binance 条件单；Runtime 仅在保护单缺失/异常时执行本地 MARKET fallback。
 * quantityBase 决定 quantityRate 的基准仓位：CURRENT 按当前仓位、INITIAL 按初始总仓位。
 *
 * @param type                         信号、事件或订单类型
 * @param quantityRate                 相对基准仓位的数量比例
 * @param reason                       信号触发原因
 * @param markFirstTakeProfitTriggered 退出完成后是否把首次止盈状态标记为已完成
 * @param exchangeProtectionType       与本地退出等价的交易所保护类型
 * @param quantityBase                 比例数量基准：CURRENT 为当前仓位，INITIAL 为初始仓位
 */
public record ExitSignal(String type, BigDecimal quantityRate, String reason, boolean markFirstTakeProfitTriggered, String exchangeProtectionType, QuantityBase quantityBase) {

    /**
     * 创建 ExitSignal 实例并初始化其依赖。
     */
    public ExitSignal(String type, BigDecimal quantityRate, String reason) {
        this(type, quantityRate, reason, false, null, QuantityBase.CURRENT);
    }

    /**
     * 创建 ExitSignal 实例并初始化其依赖。
     */
    public ExitSignal(String type, BigDecimal quantityRate, String reason, boolean markFirstTakeProfitTriggered) {
        this(type, quantityRate, reason, markFirstTakeProfitTriggered, null, QuantityBase.CURRENT);
    }

    /**
     * 创建 ExitSignal 实例并初始化其依赖。
     */
    public ExitSignal(String type, BigDecimal quantityRate, String reason, boolean markFirstTakeProfitTriggered, String exchangeProtectionType) {
        this(type, quantityRate, reason, markFirstTakeProfitTriggered, exchangeProtectionType, QuantityBase.CURRENT);
    }

    /**
     * QuantityBase，量化交易模块领域/基础设施对象。
     */
    public enum QuantityBase {
        /**
         * 以当前仓位为基准。
         */
        CURRENT,
        /**
         * 以初始总仓位为基准。
         */
        INITIAL
    }
}
