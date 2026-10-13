package org.dromara.quant.execution;

/** Sanitizes execution errors before they are shown in the trading ledger. */
public final class ExecutionErrorSanitizer {
    private ExecutionErrorSanitizer() { }

    public static String safeReason(RuntimeException ex) {
        if (ex instanceof org.dromara.common.core.exception.ServiceException
            || ex instanceof org.dromara.quant.risk.OpenCapacityService.CapacityBlockedException
            || ex instanceof org.dromara.quant.risk.LiveTradingGate.LiveTradingBlockedException
            || ex instanceof IllegalArgumentException
            || ex instanceof org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException) {
            String reason = ex.getMessage();
            if (reason != null) {
                if (java.util.regex.Pattern.compile("(?i)(authorization|api[-_]?key|secret|token|signature)\\s*[:=]").matcher(reason).find()) {
                    return "执行异常（敏感请求内容已隐藏）：" + ex.getClass().getSimpleName();
                }
                return reason.substring(0, Math.min(1500, reason.length()));
            }
        }
        return "执行异常：" + ex.getClass().getSimpleName();
    }
}
