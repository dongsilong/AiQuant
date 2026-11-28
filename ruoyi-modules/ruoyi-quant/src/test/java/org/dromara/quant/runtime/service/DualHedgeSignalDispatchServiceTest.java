package org.dromara.quant.runtime.service;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.TradingPreparationService;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyMapper;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("dev")
class DualHedgeSignalDispatchServiceTest {
    private final StrategyHedgeRoundService roundService = mock(StrategyHedgeRoundService.class);
    private final StrategyHedgeRoundExecutionService executionService = mock(StrategyHedgeRoundExecutionService.class);
    private final TradeExecutionApplicationService tradeExecutionService = mock(TradeExecutionApplicationService.class);
    private final TradingPreparationService preparationService = mock(TradingPreparationService.class);
    private final StrategyRuntimeLeaseService leaseService = mock(StrategyRuntimeLeaseService.class);
    private final QuantStrategyInstanceMapper instanceMapper = mock(QuantStrategyInstanceMapper.class);
    private final QuantStrategyMapper strategyMapper = mock(QuantStrategyMapper.class);
    private final QuantStrategySymbolMapper strategySymbolMapper = mock(QuantStrategySymbolMapper.class);
    private final QuantCredentialMapper credentialMapper = mock(QuantCredentialMapper.class);
    private final QuantProperties properties = new QuantProperties();
    private final DualHedgeSignalDispatchService service = new DualHedgeSignalDispatchService(
        roundService, executionService, tradeExecutionService, preparationService, leaseService,
        instanceMapper, strategyMapper, strategySymbolMapper, credentialMapper, properties);

    @Test
    void waitsForTradingPreparationBeforeClaimingTheSignal() {
        QuantStrategyHedgeRound round = round();
        configureAccount(round);
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenThrow(new IllegalStateException("not ready"));

        service.dispatchSignal(round);

        verify(tradeExecutionService).prepareOpenForRuntime(3L, "BTCUSDT", 10, "ISOLATED");
        verify(roundService, never()).tryBeginOpening(any());
        verify(executionService, never()).dispatchOpening(any(), any(), any(), any());
        assertEquals(StrategyHedgeRoundService.STATUS_SIGNAL_READY, round.getStatus());
    }

    @Test
    void claimsFreshSignalOnlyAfterHedgeModeIsVerifiedThenDispatches() {
        QuantStrategyHedgeRound round = round();
        configureAccount(round);
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenReturn(new TradingPreparationService.Prepared(new ExchangeGateway.PositionMode(true), null, Long.MAX_VALUE));
        when(roundService.tryBeginOpening(round)).thenReturn(true);
        when(executionService.dispatchOpening(eq(round), eq(new BigDecimal("100")), any(Instant.class), eq(7L)))
            .thenReturn(new StrategyHedgeRoundExecutionService.DispatchResult("BOTH_ACCEPTED_PENDING_FILL", "accepted"));

        service.dispatchSignal(round);

        verify(leaseService).requireCurrentOwner(2L, 3L, "BTCUSDT", 7L);
        verify(tradeExecutionService).prepareOpenForRuntime(3L, "BTCUSDT", 10, "ISOLATED");
        verify(roundService).tryBeginOpening(round);
        verify(executionService).dispatchOpening(eq(round), eq(new BigDecimal("100")), any(Instant.class), eq(7L));
    }

    @Test
    void cancelsSignalWhenHedgeModeIsNotEnabled() {
        QuantStrategyHedgeRound round = round();
        configureAccount(round);
        when(preparationService.requireReady(3L, "BTCUSDT", 10, "ISOLATED"))
            .thenReturn(new TradingPreparationService.Prepared(new ExchangeGateway.PositionMode(false), null, Long.MAX_VALUE));

        service.dispatchSignal(round);

        verify(roundService).abortSignalReady(round);
        verify(roundService, never()).tryBeginOpening(any());
        verifyNoInteractions(executionService);
    }

    @Test
    void cancelsExpiredSignalWithoutPreparingTrading() {
        QuantStrategyHedgeRound round = round();
        round.setSignalEventTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(10));
        properties.getMarket().setMaxEventAgeMillis(3_000);

        service.dispatchSignal(round);

        verify(roundService).abortSignalReady(round);
        verifyNoInteractions(tradeExecutionService, preparationService, executionService);
    }

    private void configureAccount(QuantStrategyHedgeRound round) {
        QuantStrategyInstance instance = new QuantStrategyInstance();
        instance.setId(2L);
        instance.setStrategyId(1L);
        instance.setStrategySymbolId(4L);
        instance.setCredentialId(3L);
        instance.setStatus("RUNNING");
        when(instanceMapper.selectById(2L)).thenReturn(instance);

        QuantStrategy strategy = new QuantStrategy();
        strategy.setId(1L);
        strategy.setPluginCode("dual_hedge_slope");
        strategy.setStatus("1");
        strategy.setDefaultLeverage(10);
        when(strategyMapper.selectById(1L)).thenReturn(strategy);

        QuantStrategySymbol binding = new QuantStrategySymbol();
        binding.setId(4L);
        binding.setStrategyId(1L);
        binding.setStatus("0");
        binding.setRuntimeEnabled(true);
        binding.setIntervalCode("15m");
        binding.setLeverage(10);
        binding.setMarginMode("ISOLATED");
        when(strategySymbolMapper.selectById(4L)).thenReturn(binding);

        QuantCredential credential = new QuantCredential();
        credential.setId(3L);
        credential.setStatus("0");
        credential.setRunMode("TESTNET");
        credential.setCredentialRuntimeVersion(7L);
        when(credentialMapper.selectById(3L)).thenReturn(credential);
        doNothing().when(leaseService).requireCurrentOwner(2L, 3L, "BTCUSDT", 7L);
    }

    private static QuantStrategyHedgeRound round() {
        QuantStrategyHedgeRound round = new QuantStrategyHedgeRound();
        round.setId(11L);
        round.setVersion(0);
        round.setHedgeRoundKey("round-1");
        round.setStrategyId(1L);
        round.setInstanceId(2L);
        round.setCredentialId(3L);
        round.setSymbol("BTCUSDT");
        round.setStatus(StrategyHedgeRoundService.STATUS_SIGNAL_READY);
        round.setLongLegStatus(StrategyHedgeRoundService.LEG_NOT_STARTED);
        round.setShortLegStatus(StrategyHedgeRoundService.LEG_NOT_STARTED);
        round.setReferencePrice(new BigDecimal("100"));
        round.setSignalEventTime(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMillis(100));
        round.setTargetNotionalPerLeg(new BigDecimal("1000"));
        round.setLeverage(10);
        return round;
    }
}
