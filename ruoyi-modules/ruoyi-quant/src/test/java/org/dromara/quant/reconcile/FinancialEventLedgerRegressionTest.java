package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.account.mapper.QuantAccountSnapshotMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.decision.TradeDecisionLogService;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.StrictBinanceTradeCollector;
import org.dromara.quant.execution.StrictBinanceIncomeCollector;
import org.dromara.quant.execution.StrategyFillJournalService;
import org.dromara.quant.finance.domain.QuantFinancialEvent;
import org.dromara.quant.finance.domain.vo.QuantFinancialEventVo;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.dromara.quant.reconcile.mapper.LivePositionSnapshotFenceMapper;
import org.dromara.quant.reconcile.mapper.QuantReconcileRecordMapper;
import org.dromara.quant.reconcile.mapper.StrategyFillSyncCursorMapper;
import org.dromara.quant.reconcile.mapper.FinancialEventSyncCursorMapper;
import org.dromara.quant.risk.mapper.OpenCapacityMapper;
import org.dromara.quant.runtime.service.StrategyPositionStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class FinancialEventLedgerRegressionTest {
    private final QuantFinancialEventMapper financialEvents = mock(QuantFinancialEventMapper.class);
    private TradingReconciliationService service;

    @BeforeEach
    void setUp() {
        service = new TradingReconciliationService(
            null, mock(QuantAccountSnapshotMapper.class), mock(QuantPositionMapper.class),
            mock(QuantOrderMapper.class), mock(QuantTradeMapper.class), mock(QuantProtectionOrderMapper.class),
            financialEvents, mock(QuantReconcileRecordMapper.class), mock(ReconciliationFailureRecorder.class),
            mock(StrategyPositionStateService.class), mock(PlatformTransactionManager.class),
            mock(StrictBinanceTradeCollector.class), mock(StrictBinanceIncomeCollector.class), mock(StrategyFillJournalService.class),
            mock(StrategyFillSyncCursorMapper.class), mock(StrategyFillSyncCursorService.class),
            mock(FinancialEventSyncCursorMapper.class), mock(FinancialEventSyncCursorService.class));
    }

    private void sync(List<ExchangeGateway.IncomeSnapshot> snapshots) throws Exception {
        Method method = TradingReconciliationService.class.getDeclaredMethod(
            "syncFinancialEvents", QuantCredential.class, List.class);
        method.setAccessible(true);
        try {
            method.invoke(service, credential(), snapshots);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    private QuantCredential credential() {
        QuantCredential credential = new QuantCredential();
        credential.setId(99L);
        return credential;
    }

    private ExchangeGateway.IncomeSnapshot income(BigDecimal amount, Instant time) {
        return new ExchangeGateway.IncomeSnapshot("BTCUSDT", "FUNDING_FEE", amount, "USDT", 7001L, "funding", time);
    }

    @Test
    void rejectsMissingFinancialFactsInsteadOfInventingZeroOrCurrentTime() {
        assertThrows(IllegalStateException.class, () -> sync(List.of(income(null, Instant.parse("2026-10-20T00:00:00Z")))));
        verifyNoInteractions(financialEvents);
    }

    @Test
    void insertsExactExchangeAmountAndUtcEventTime() throws Exception {
        when(financialEvents.selectVoOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(financialEvents.insert(any(QuantFinancialEvent.class))).thenReturn(1);
        Instant timestamp = Instant.parse("2026-10-20T00:00:00.123Z");

        sync(List.of(income(new BigDecimal("-0.125"), timestamp)));

        var captor = org.mockito.ArgumentCaptor.forClass(QuantFinancialEvent.class);
        verify(financialEvents).insert(captor.capture());
        assertEquals(new BigDecimal("-0.125"), captor.getValue().getAmount());
        assertEquals(LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC), captor.getValue().getEventTime());
        assertEquals(7001L, captor.getValue().getExchangeTransactionId());
    }

    @Test
    void allowsDifferentIncomeTypesToShareBinanceTransactionId() throws Exception {
        when(financialEvents.selectVoOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(financialEvents.insert(any(QuantFinancialEvent.class))).thenReturn(1);
        Instant timestamp = Instant.parse("2026-10-20T00:00:00Z");

        ExchangeGateway.IncomeSnapshot commission =
            new ExchangeGateway.IncomeSnapshot("BTCUSDT", "COMMISSION", new BigDecimal("-0.125"), "USDT", 7001L, "commission", timestamp);
        ExchangeGateway.IncomeSnapshot funding =
            new ExchangeGateway.IncomeSnapshot("BTCUSDT", "FUNDING_FEE", new BigDecimal("0.125"), "USDT", 7001L, "funding", timestamp);

        sync(List.of(commission, funding));

        var captor = org.mockito.ArgumentCaptor.forClass(QuantFinancialEvent.class);
        verify(financialEvents, times(2)).insert(captor.capture());
        assertEquals(List.of("COMMISSION", "FUNDING_FEE"),
            captor.getAllValues().stream().map(QuantFinancialEvent::getEventType).toList());
    }

    @Test
    void rejectsReplayThatConflictsWithPersistedExchangeFact() throws Exception {
        QuantFinancialEventVo stored = new QuantFinancialEventVo();
        stored.setSymbol("BTCUSDT");
        stored.setEventType("FUNDING_FEE");
        stored.setAmount(new BigDecimal("0.125"));
        stored.setAsset("USDT");
        stored.setInfo("funding");
        stored.setEventTime(LocalDateTime.of(2026, 10, 20, 0, 0));
        when(financialEvents.selectVoOne(any(LambdaQueryWrapper.class))).thenReturn(stored);

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
            () -> sync(List.of(income(new BigDecimal("-0.125"), Instant.parse("2026-10-20T00:00:00Z")))));
        assertTrue(conflict.getMessage().contains("fields=amount"),
            "conflict diagnostics should identify the exact mismatched financial field");
        verify(financialEvents, never()).insert(any(QuantFinancialEvent.class));
    }

    @Test
    void normalizesOnlyAnOtherwiseIdenticalLegacyBeijingLocalEventTime() throws Exception {
        QuantFinancialEventVo stored = new QuantFinancialEventVo();
        stored.setId(123L);
        stored.setSymbol("BTCUSDT");
        stored.setEventType("FUNDING_FEE");
        stored.setAmount(new BigDecimal("-0.125"));
        stored.setAsset("USDT");
        stored.setInfo("funding");
        stored.setEventTime(LocalDateTime.of(2026, 10, 20, 8, 0));
        when(financialEvents.selectVoOne(any(LambdaQueryWrapper.class))).thenReturn(stored);
        when(financialEvents.updateById(any(QuantFinancialEvent.class))).thenReturn(1);

        Instant timestamp = Instant.parse("2026-10-20T00:00:00Z");
        assertDoesNotThrow(() -> sync(List.of(income(new BigDecimal("-0.125"), timestamp))));

        var captor = org.mockito.ArgumentCaptor.forClass(QuantFinancialEvent.class);
        verify(financialEvents).updateById(captor.capture());
        assertEquals(LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC), captor.getValue().getEventTime());
    }

    @Test
    void identicalReplayIsIdempotentAndDoesNotRewriteOriginalRow() throws Exception {
        QuantFinancialEventVo stored = new QuantFinancialEventVo();
        stored.setSymbol("BTCUSDT");
        stored.setEventType("FUNDING_FEE");
        stored.setAmount(new BigDecimal("-0.125"));
        stored.setAsset("USDT");
        stored.setInfo("funding");
        stored.setEventTime(LocalDateTime.of(2026, 10, 20, 0, 0));
        when(financialEvents.selectVoOne(any(LambdaQueryWrapper.class))).thenReturn(stored);

        assertDoesNotThrow(() -> sync(List.of(income(new BigDecimal("-0.125"), Instant.parse("2026-10-20T00:00:00.999Z")))));
        verify(financialEvents, never()).insert(any(QuantFinancialEvent.class));
        verify(financialEvents, never()).updateById(any(QuantFinancialEvent.class));
    }
}
