package org.dromara.quant.risk;

import org.dromara.quant.reconcile.LivePositionFreshness;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.risk.domain.QuantRiskConfig;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LivePositionFreshnessRiskTest {
    @Test
    void liveEntriesRequireVerifiedFullAccountPositionSnapshot() {
        var risks = mock(QuantRiskConfigMapper.class);
        var positions = mock(QuantPositionMapper.class);
        var executions = mock(QuantExecutionRecordMapper.class);
        var trades = mock(QuantTradeMapper.class);
        var finance = mock(QuantFinancialEventMapper.class);
        var config = new QuantRiskConfig();
        config.setWhitelist(true);
        config.setLiveEnabled(true);
        config.setMaxOpenPositions(2);
        when(risks.selectOne(any())).thenReturn(config);
        when(positions.selectCount(any())).thenReturn(0L);
        var service = new RiskDecisionService(risks,positions,executions,trades,finance);
        var freshness = new LivePositionFreshness();
        ReflectionTestUtils.setField(service,"positionFreshness",freshness);
        assertFalse(service.approveOpen(11L,"BTCUSDT",BigDecimal.ONE,"LIVE"));
        verifyNoInteractions(positions);
        freshness.verified(11L);
        assertTrue(service.approveOpen(11L,"BTCUSDT",BigDecimal.ONE,"LIVE"));
        freshness.invalidate(11L);
        assertFalse(service.approveOpen(11L,"BTCUSDT",BigDecimal.ONE,"LIVE"));
    }
}
