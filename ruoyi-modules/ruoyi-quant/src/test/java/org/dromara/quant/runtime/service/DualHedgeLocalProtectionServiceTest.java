package org.dromara.quant.runtime.service;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.exchange.ExchangeTradingRuleCache;
import org.dromara.quant.execution.ExecutionRecordService;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.ProtectionOrderService;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService.LeaseSnapshot;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DualHedgeLocalProtectionServiceTest {
    private final StrategyPositionStateService stateService = mock(StrategyPositionStateService.class);
    private final QuantPositionMapper positionMapper = mock(QuantPositionMapper.class);
    private final QuantCredentialMapper credentialMapper = mock(QuantCredentialMapper.class);
    private final QuantStrategyInstanceMapper instanceMapper = mock(QuantStrategyInstanceMapper.class);
    private final StrategyRuntimeLeaseService leaseService = mock(StrategyRuntimeLeaseService.class);
    private final MarketStreamHub marketStreamHub = mock(MarketStreamHub.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final QuantProperties properties = new QuantProperties();
    private final ExchangeTradingRuleCache tradingRuleCache = mock(ExchangeTradingRuleCache.class);
    private final TradeExecutionApplicationService executionService = mock(TradeExecutionApplicationService.class);
    private final ExecutionRecordService executionRecordService = mock(ExecutionRecordService.class);
    private final org.dromara.quant.exchange.AccountPositionSnapshotQuery positionSnapshotQuery =
        mock(org.dromara.quant.exchange.AccountPositionSnapshotQuery.class);
    private final ProtectionOrderService protectionOrderService = mock(ProtectionOrderService.class);
    private final StrategyHedgeRoundService roundService = mock(StrategyHedgeRoundService.class);

    @Test
    void closesExistingRuntimeWhenCredentialBecomesPaper() throws Exception {
        QuantStrategyPositionState state = state();
        QuantPosition position = position();
        QuantStrategyInstance instance = new QuantStrategyInstance();
        instance.setId(2L);
        instance.setCredentialId(3L);
        QuantCredential credential = new QuantCredential();
        credential.setId(3L);
        credential.setStatus("0");
        credential.setRunMode("TESTNET");
        credential.setCredentialRuntimeVersion(9L);
        AutoCloseable subscription = mock(AutoCloseable.class);

        when(stateService.findUnclosed()).thenReturn(List.of(state));
        when(positionMapper.selectById(201L)).thenReturn(position);
        when(instanceMapper.selectById(2L)).thenReturn(instance);
        when(credentialMapper.selectById(3L)).thenReturn(credential);
        when(leaseService.acquireOrRenewSnapshot(2L, 3L, "BTCUSDT", 9L))
            .thenReturn(new LeaseSnapshot(1L, Long.MAX_VALUE));
        when(marketStreamHub.subscribe(eq("TESTNET"), eq("BTCUSDT"), eq("15m"), any(), any()))
            .thenReturn(subscription);

        DualHedgeLocalProtectionService service = new DualHedgeLocalProtectionService(
            stateService, positionMapper, credentialMapper, instanceMapper, leaseService, marketStreamHub,
            gateway, properties, tradingRuleCache, executionService, executionRecordService,
            positionSnapshotQuery, protectionOrderService, roundService);
        try {
            service.reconcile();
            verify(marketStreamHub, times(1))
                .subscribe(eq("TESTNET"), eq("BTCUSDT"), eq("15m"), any(), any());

            credential.setRunMode("PAPER");
            service.reconcile();

            verify(subscription, times(1)).close();
            verify(marketStreamHub, times(1))
                .subscribe(eq("TESTNET"), eq("BTCUSDT"), eq("15m"), any(), any());
        } finally {
            service.destroy();
        }
    }

    private static QuantStrategyPositionState state() {
        QuantStrategyPositionState state = new QuantStrategyPositionState();
        state.setInstanceId(2L);
        state.setLegKey("LONG");
        state.setHedgeRoundKey("round-1");
        state.setPositionCycleKey("round-1:LONG");
        state.setPositionId(201L);
        state.setStatus("CLOSING");
        state.setDirection("LONG");
        state.setPositionQty(new BigDecimal("2"));
        return state;
    }

    private static QuantPosition position() {
        QuantPosition position = new QuantPosition();
        position.setId(201L);
        position.setCredentialId(3L);
        position.setSymbol("BTCUSDT");
        position.setPositionSide("LONG");
        position.setQuantity(new BigDecimal("2"));
        position.setEntryPrice(new BigDecimal("100"));
        return position;
    }
}
