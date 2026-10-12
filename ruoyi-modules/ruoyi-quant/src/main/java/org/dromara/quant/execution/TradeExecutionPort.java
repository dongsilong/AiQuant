package org.dromara.quant.execution;

/**
 * 统一交易执行端口，手工交易和策略交易都必须通过此接口。
 */
public interface TradeExecutionPort {
    /**
     * 执行统一开仓流程，并通过执行记录保证幂等。
     */
    ExecutionResult open(OpenPositionCommand command);

    /**
     * 执行减仓或全平流程。
     */
    ExecutionResult close(ClosePositionCommand command);

    /**
     * 执行撤单流程。
     */
    ExecutionResult cancel(CancelOrderCommand command);
}
