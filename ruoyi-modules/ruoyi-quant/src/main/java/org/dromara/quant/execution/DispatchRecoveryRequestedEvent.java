package org.dromara.quant.execution;

/** 派发结果进入 UNKNOWN 后，通知异步恢复协调器核实持久化票据。 */
public record DispatchRecoveryRequestedEvent(Long credentialId, String symbol) {
}
