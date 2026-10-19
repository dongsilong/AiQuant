package org.dromara.quant.execution;

/**
 * 派发槽冲突事件。只在预留事务回滚后发布，供异步核实当前占用槽的旧操作。
 */
public record DispatchBlockedEvent(Long credentialId, String symbol, String requestedOperation) {
}
