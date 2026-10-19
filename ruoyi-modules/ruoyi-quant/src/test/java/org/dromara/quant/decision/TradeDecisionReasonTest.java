package org.dromara.quant.decision;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeDecisionReasonTest {
    @Test
    void plannedReasonIncludesQuantityAndUsdtNotionalWithoutCallingItActual() {
        String reason = TradeDecisionLogService.withPlannedQuantityAndNotional("达到开仓条件", "OPEN", new BigDecimal("2.5"), new BigDecimal("100"));

        assertEquals("达到开仓条件；计划开仓数量=2.5；按参考价估算名义金额=250 USDT", reason);
    }
}
