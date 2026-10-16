package org.dromara.quant.execution;

/**
 * 统一交易执行结果，描述是否接受、订单状态和交易所订单标识。
 *
 * @param accepted      执行是否已被接受
 * @param status        当前状态
 * @param orderId       交易所订单主键
 * @param clientOrderId 客户端订单号
 */
public record ExecutionResult(boolean accepted, String status,
                              @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) Long orderId,
                              String clientOrderId) {
    /**
     * 构建一条未通过风控或执行检查的拒绝结果。
     */
    public static ExecutionResult rejected(String status) {
        return new ExecutionResult(false, status, null, null);
    }
}
