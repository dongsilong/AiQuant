package org.dromara.quant.execution;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.AccountPositionCache;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.paper.PaperTradingService;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.risk.OpenCapacityService;
import org.dromara.quant.risk.RiskDecisionService;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ClosedOpenReservationPreparationTest {
    private final AccountPositionCache positions = mock(AccountPositionCache.class);
    private final OpenCapacityService capacity = mock(OpenCapacityService.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);

    private TradeExecutionApplicationService service() {
        var service = new TradeExecutionApplicationService(mock(QuantCredentialMapper.class), mock(QuantSymbolMapper.class),
            mock(QuantRiskConfigMapper.class), mock(RiskDecisionService.class), mock(TradeExecutionPort.class),
            new QuantProperties(), gateway, mock(QuantPositionMapper.class), mock(PaperTradingService.class),
            mock(TradingPreparationService.class));
        ReflectionTestUtils.setField(service, "positionCache", positions);
        ReflectionTestUtils.setField(service, "openCapacityService", capacity);
        return service;
    }

    @Test
    void cleanupUsesOnlyTrustedCacheAndDoesNotQueryExchange() {
        when(positions.requirePositions(1L)).thenReturn(List.of());
        service().releaseClosedOpenReservationsForRuntime(1L, 100L, "ENAUSDT", "TESTNET");
        verify(positions).requirePositions(1L);
        verify(capacity).releaseClosedForInstance(1L, 100L, "ENAUSDT", List.of());
        verifyNoInteractions(gateway);
    }

    @Test
    void unavailableCacheCannotReleaseHistoricalCapacity() {
        when(positions.requirePositions(1L)).thenThrow(new OpenCapacityService.CapacityBlockedException("snapshot stale"));
        assertThrows(OpenCapacityService.CapacityBlockedException.class,
            () -> service().releaseClosedOpenReservationsForRuntime(1L, 100L, "ENAUSDT", "LIVE"));
        verifyNoInteractions(capacity, gateway);
    }

    @Test
    void paperCleanupLeavesPositionValidationToLocalLedger() {
        service().releaseClosedOpenReservationsForRuntime(1L, 100L, "ENAUSDT", "PAPER");
        verify(capacity).releaseClosedForInstance(1L, 100L, "ENAUSDT", List.of());
        verifyNoInteractions(positions, gateway);
    }
}
