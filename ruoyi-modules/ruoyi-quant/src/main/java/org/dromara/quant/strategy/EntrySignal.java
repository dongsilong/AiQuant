package org.dromara.quant.strategy;

/**
 * 策略开仓信号，描述开仓方向及触发原因。
 *
 * @param direction 策略方向，取 LONG 或 SHORT
 * @param reason    信号触发原因
 */
public record EntrySignal(Direction direction, String reason) {

    /**
     * Direction，量化交易模块领域/基础设施对象。
     */
    public enum Direction {
        LONG, SHORT
    }
}
