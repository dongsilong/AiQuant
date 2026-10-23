package org.dromara.quant.risk;

import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.market.AccountPositionCache;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.risk.domain.QuantRiskConfig;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class RiskSignedPositionCountTest {
    @Test
    void livePositionLimitUsesFreshExchangePositionFactsInsteadOfStaleDatabaseProjection() {
        var risks = mock(QuantRiskConfigMapper.class);
        var positions = mock(QuantPositionMapper.class);
        var execution = mock(QuantExecutionRecordMapper.class);
        var trades = mock(QuantTradeMapper.class);
        var finance = mock(QuantFinancialEventMapper.class);
        var risk = new QuantRiskConfig();
        risk.setWhitelist(true);
        risk.setLiveEnabled(true);
        risk.setMaxOpenPositions(1);
        when(risks.selectOne(any())).thenReturn(risk);
        var service = new RiskDecisionService(risks, positions, execution, trades, finance);
        var freshness = new org.dromara.quant.reconcile.LivePositionFreshness();
        freshness.verified(11L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "positionFreshness", freshness);
        var positionCache = mock(AccountPositionCache.class);
        when(positionCache.requirePositions(11L)).thenReturn(List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "positionCache", positionCache);
        assertTrue(service.approveOpen(11L, "BTCUSDT", BigDecimal.TEN, "LIVE"));
        verify(positionCache).requirePositions(11L);
        verify(positions, never()).selectCount(any());
    }

    @Test
    void shortPositionsWithNegativeQuantityAreIncludedInRiskCount() {
        var risks = mock(QuantRiskConfigMapper.class);
        var positions = mock(QuantPositionMapper.class);
        var execution = mock(QuantExecutionRecordMapper.class);
        var trades = mock(QuantTradeMapper.class);
        var finance = mock(QuantFinancialEventMapper.class);
        var risk = new QuantRiskConfig();
        risk.setWhitelist(true);
        risk.setLiveEnabled(true);
        risk.setMaxOpenPositions(1);
        when(risks.selectOne(any())).thenReturn(risk);
        var service = new RiskDecisionService(risks, positions, execution, trades, finance);
        var freshness = new org.dromara.quant.reconcile.LivePositionFreshness();
        freshness.verified(11L);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "positionFreshness", freshness);
        var positionCache = mock(AccountPositionCache.class);
        when(positionCache.requirePositions(11L)).thenReturn(List.of(new ExchangeGateway.PositionSnapshot("BTCUSDT", "SHORT", BigDecimal.ONE.negate(), null, null, null, null, null,
            null, null, null)));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "positionCache", positionCache);
        assertFalse(service.approveOpen(11L, "BTCUSDT", BigDecimal.TEN, "LIVE"));
        verify(positionCache).requirePositions(11L);
        verify(positions, never()).selectCount(any());
    }
}
