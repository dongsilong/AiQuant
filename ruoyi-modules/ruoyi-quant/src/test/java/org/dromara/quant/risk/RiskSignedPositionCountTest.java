package org.dromara.quant.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.risk.domain.QuantRiskConfig;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class RiskSignedPositionCountTest {
    @SuppressWarnings("unchecked")
    @Test
    void shortPositionsWithNegativeQuantityAreIncludedInRiskCount() {
        var risks = mock(QuantRiskConfigMapper.class);
        var positions = mock(QuantPositionMapper.class);
        var execution = mock(QuantExecutionRecordMapper.class);
        var trades = mock(QuantTradeMapper.class);
        var finance = mock(QuantFinancialEventMapper.class);
        var risk = new QuantRiskConfig();
        risk.setWhitelist(true);
        risk.setMaxOpenPositions(1);
        when(risks.selectOne(any())).thenReturn(risk);
        when(positions.selectCount(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<QuantPosition> query = inv.getArgument(0);
            String sql = query.getSqlSegment();
            assertTrue(sql.contains("<>"), "Non-zero signed positions must be counted: " + sql);
            return 1L;
        });
        var service = new RiskDecisionService(risks, positions, execution, trades, finance);
        assertFalse(service.approveOpen(11L, "BTCUSDT", BigDecimal.TEN, "LIVE"));
        verify(positions).selectCount(any());
    }
}
