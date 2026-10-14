package org.dromara.quant.reconcile;

import org.dromara.quant.account.mapper.QuantAccountSnapshotMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.dromara.quant.reconcile.mapper.QuantReconcileRecordMapper;
import org.dromara.quant.runtime.service.StrategyPositionStateService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * No network call may run in the transaction that persists a complete exchange snapshot.
 * Missing local position data must NEVER trigger Binance cancelProtectionOrder.
 */
@Tag("dev")
class TradingReconciliationTransactionTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final QuantAccountSnapshotMapper accounts = mock(QuantAccountSnapshotMapper.class);
    private final QuantPositionMapper positions = mock(QuantPositionMapper.class);
    private final QuantOrderMapper orders = mock(QuantOrderMapper.class);
    private final QuantTradeMapper trades = mock(QuantTradeMapper.class);
    private final QuantProtectionOrderMapper protection = mock(QuantProtectionOrderMapper.class);
    private final QuantFinancialEventMapper finance = mock(QuantFinancialEventMapper.class);
    private final QuantReconcileRecordMapper records = mock(QuantReconcileRecordMapper.class);
    private final ReconciliationFailureRecorder errors = mock(ReconciliationFailureRecorder.class);
    private final StrategyPositionStateService state = mock(StrategyPositionStateService.class);

    private TradingReconciliationService service() {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:reconciliation_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        return new TradingReconciliationService(gateway, accounts, positions, orders, trades,
            protection, finance, records, errors, state, new DataSourceTransactionManager(source));
    }

    private QuantCredential credential() {
        var value = new QuantCredential();
        value.setId(1L);
        value.setApiKey("test-key");
        value.setSecretKey("test-secret");
        value.setRunMode("LIVE");
        return value;
    }

    private void stubCommonFacts() {
        when(gateway.queryAccount(any())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "Binance called inside DB transaction");
            return new ExchangeGateway.AccountSnapshot(BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO);
        });
        when(gateway.queryPositions(any(), isNull())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return List.of();
        });
        when(gateway.queryOrders(any(), isNull())).thenReturn(List.of());
        when(gateway.queryOrderHistory(any(), isNull(), any(), anyLong())).thenReturn(List.of());
        when(gateway.queryIncomeHistory(any(), isNull(), isNull(), any(), anyLong())).thenReturn(List.of());
        doAnswer(invocation -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(), "DB write not inside short transaction");
            return 1;
        }).when(accounts).insert(any(org.dromara.quant.account.domain.QuantAccountSnapshot.class));
    }

    @Test
    void collectsAllExchangeFactsBeforeOpeningDatabaseWriteTransaction() {
        stubCommonFacts();
        service().reconcileCredential(credential(), 48);
        verify(accounts).insert(any(org.dromara.quant.account.domain.QuantAccountSnapshot.class));
        verify(records).insert(any(org.dromara.quant.reconcile.domain.QuantReconcileRecord.class));
        verify(gateway, never()).cancelProtectionOrder(any());
    }

    @Test
    void failedNetworkCollectionCannotPersistPartialAccountFacts() {
        stubCommonFacts();
        when(gateway.queryOrderHistory(any(), isNull(), any(), anyLong()))
            .thenThrow(new IllegalStateException("Binance unavailable"));
        assertThrows(IllegalStateException.class, () -> service().reconcileCredential(credential(), 48));
        verifyNoInteractions(accounts);
        verify(errors).record(eq(1L), anyString());
    }

    @Test
    void orphanProtectionDoesNotGetCanceledJustBecauseLocalPositionIsMissing() {
        stubCommonFacts();
        var open = new ExchangeGateway.OrderSnapshot("BTCUSDT", 7L, "open7", "BUY", "MARKET", "NEW",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, Instant.EPOCH);
        when(gateway.queryOrders(any(), isNull())).thenReturn(List.of(open));
        when(gateway.queryTrades(any(), eq("BTCUSDT"), any())).thenReturn(List.of());
        when(gateway.queryProtectionOrders(any(), eq("BTCUSDT"))).thenReturn(List.of(
            new ExchangeGateway.ProtectionOrderSnapshot("BTCUSDT", 123L, "protect123", "NEW",
                "SELL", "LONG", "STOP_MARKET", BigDecimal.ONE, BigDecimal.ONE, Instant.EPOCH)));
        service().reconcileCredential(credential(), 48);
        verify(gateway, never()).cancelProtectionOrder(any());
        verify(protection).insert(any(org.dromara.quant.protection.domain.QuantProtectionOrder.class));
        var record = org.mockito.ArgumentCaptor.forClass(org.dromara.quant.reconcile.domain.QuantReconcileRecord.class);
        verify(records).insert(record.capture());
        assertEquals("DEGRADED", record.getValue().getResult());
        assertTrue(record.getValue().getDetailJson().contains("unmatchedLiveProtections"));
    }
}
