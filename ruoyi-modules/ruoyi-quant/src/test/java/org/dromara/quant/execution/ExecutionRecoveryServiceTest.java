package org.dromara.quant.execution;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ExecutionRecoveryServiceTest {
    @Test
    void scansUnresolvedOrdersWithoutDiscardingThoseOlderThanOneDay() {
        var records = mock(QuantExecutionRecordMapper.class);
        when(records.selectList(any())).thenReturn(List.of());
        var service = new ExecutionRecoveryService(
            records, mock(QuantCredentialMapper.class), mock(ExchangeGateway.class),
            mock(ExecutionRecordService.class), mock(OrderLedgerService.class),
            new QuantProperties());
        service.recover();
        verify(records).selectList(argThat(wrapper -> {
            String sql = wrapper.getSqlSegment().toLowerCase(java.util.Locale.ROOT);
            return sql.contains("last_recovery_at") && sql.contains("created_at")
                && !sql.contains("updated_at") && sql.contains("limit 100");
        }));
    }

    @Test
    void fallbackOrderMatchUsesExchangeStatusRatherThanStaleLocalStatus() {
        var records = mock(QuantExecutionRecordMapper.class);
        var credentials = mock(QuantCredentialMapper.class);
        var gateway = mock(ExchangeGateway.class);
        var recovery = mock(ExecutionRecordService.class);
        var ledger = mock(OrderLedgerService.class);

        var record = new QuantExecutionRecord();
        record.setCredentialId(1L);
        record.setSymbol("BTCUSDT");
        record.setClientOrderId("client-123");
        record.setOperationType("OPEN");
        record.setExchangeOrderStatus("NEW");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now().minusDays(3));
        when(records.selectList(any())).thenReturn(List.of(record));

        var credential = new QuantCredential();
        credential.setApiKey("test");
        credential.setSecretKey("test");
        credential.setSignatureType("HMAC");
        credential.setRunMode("LIVE");
        when(credentials.selectById(1L)).thenReturn(credential);

        var exchangeOrder = new ExchangeGateway.OrderSnapshot(
            "BTCUSDT", 123L, "client-123", "BUY", "MARKET", "FILLED",
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, Instant.now());
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of(exchangeOrder));

        new ExecutionRecoveryService(records, credentials, gateway, recovery, ledger, new QuantProperties()).recover();

        verify(ledger).syncSnapshot(1L, exchangeOrder);
        verify(recovery).markRecovered(record, 123L, "FILLED");
        verify(gateway, never()).placeOrder(any());
    }
}
