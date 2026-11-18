package org.dromara.quant.execution;

import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.risk.mapper.OpenCapacityMapper;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.runtime.mapper.QuantStrategyPositionStateMapper;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class TestnetOpenNotExecutedRecoveryServiceTest {
    private final LiveRecoveryApprovalMapper cases = mock(LiveRecoveryApprovalMapper.class);
    private final LiveRecoveryAuditMapper audit = mock(LiveRecoveryAuditMapper.class);
    private final LiveOrderDispatchMapper dispatch = mock(LiveOrderDispatchMapper.class);
    private final QuantExecutionRecordMapper executions = mock(QuantExecutionRecordMapper.class);
    private final QuantCredentialMapper credentials = mock(QuantCredentialMapper.class);
    private final StrategyOrderIntentMapper intents = mock(StrategyOrderIntentMapper.class);
    private final QuantStrategyPositionStateMapper positionStates = mock(QuantStrategyPositionStateMapper.class);
    private final QuantOrderMapper orders = mock(QuantOrderMapper.class);
    private final OpenCapacityMapper capacity = mock(OpenCapacityMapper.class);
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TestnetOpenNotExecutedRecoveryService service = new TestnetOpenNotExecutedRecoveryService(
        cases, audit, dispatch, executions, credentials, intents, positionStates, orders, capacity, gateway, manager);

    private Map<String, Object> approvedCase() {
        Map<String, Object> row = new HashMap<>();
        row.put("state", "APPROVED");
        row.put("evidence_sha256", "a".repeat(64));
        row.put("requested_by", 10L);
        row.put("reviewed_by", 11L);
        row.put("credential_id", 20L);
        row.put("symbol", "BTCUSDT");
        row.put("dispatch_key", "key-1");
        row.put("operation_type", "OPEN");
        row.put("expected_client_order_id", "client-1");
        row.put("expected_exchange_order_id", null);
        return row;
    }

    private QuantCredential testnetCredential() {
        QuantCredential credential = new QuantCredential();
        credential.setRunMode("TESTNET");
        return credential;
    }

    private QuantExecutionRecord unknownOpen() {
        QuantExecutionRecord record = new QuantExecutionRecord();
        record.setId(100L);
        record.setCredentialId(20L);
        record.setStrategyId(30L);
        record.setStrategyInstanceId(40L);
        record.setOperationType("OPEN");
        record.setSymbol("BTCUSDT");
        record.setIdempotencyKey("key-1");
        record.setClientOrderId("client-1");
        record.setExecutionStatus("UNKNOWN");
        record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC).minusHours(1));
        return record;
    }

    private QuantStrategyPositionState pendingState() {
        QuantStrategyPositionState state = new QuantStrategyPositionState();
        state.setId(50L);
        state.setInstanceId(40L);
        state.setStatus("OPEN_PENDING");
        state.setDirection("LONG");
        state.setPositionQty(BigDecimal.ONE);
        state.setPendingAction("OPEN");
        state.setPendingIdempotencyKey("key-1");
        state.setPendingClientOrderId("client-1");
        state.setPositionCycleKey("cycle-1");
        state.setRecoveryRequired(true);
        state.setUnsafeRecovery(true);
        state.setVersion(7);
        return state;
    }

    private void stubVerifiedAbsence() {
        when(cases.findCase(1L)).thenReturn(approvedCase());
        when(credentials.selectById(20L)).thenReturn(testnetCredential());
        when(executions.selectOne(any())).thenReturn(unknownOpen());
        when(dispatch.activeDispatch(20L, "BTCUSDT")).thenReturn(Map.of(
            "dispatchKey", "key-1", "clientOrderId", "client-1", "operationType", "OPEN",
            "dispatchStatus", "UNKNOWN", "strategyInstanceId", 40L));
        Map<String, Object> pre = new HashMap<>();
        pre.put("strategy_id", 30L);
        pre.put("strategy_instance_id", 40L);
        pre.put("symbol", "BTCUSDT");
        pre.put("client_order_id", "client-1");
        pre.put("operation_type", "OPEN");
        pre.put("position_cycle_key", "cycle-1");
        pre.put("position_side", "LONG");
        pre.put("exchange_side", "BUY");
        when(intents.pending(20L, "key-1")).thenReturn(pre);
        when(positionStates.selectOne(any())).thenReturn(pendingState());
        when(gateway.queryOrder(any(), eq("BTCUSDT"), isNull(), eq("client-1"))).thenReturn(null);
        when(gateway.queryOrders(any(), eq("BTCUSDT"))).thenReturn(List.of());
        when(gateway.queryOrderHistoryComplete(any(), eq("BTCUSDT"), any(), eq(1000L))).thenReturn(List.of());
        when(gateway.queryTrades(any(), eq("BTCUSDT"), any())).thenReturn(List.of());
        when(gateway.queryPositions(any(), eq("BTCUSDT"))).thenReturn(List.of());
        when(capacity.countActiveLocalPositions(20L, "BTCUSDT")).thenReturn(0L);
        when(executions.markNotExecutedAfterVerifiedAbsence(eq(100L), eq(20L), eq("BTCUSDT"), eq("key-1"), eq("client-1"), anyString(), any()))
            .thenReturn(1);
        when(positionStates.closeUnexecutedOpenPending(eq(50L), eq(40L), eq(7), eq("key-1"), eq("client-1"), eq("cycle-1"), any())).thenReturn(1);
        when(cases.markExecuted(1L, "a".repeat(64))).thenReturn(1);
        when(dispatch.markNotExecuted(20L, "BTCUSDT", "key-1", "client-1", 40L)).thenReturn(1);
        when(dispatch.release(20L, "BTCUSDT", "key-1")).thenReturn(1);
        when(capacity.releaseFailed(20L, "key-1")).thenReturn(1);
        when(audit.append(anyLong(), eq(1L), eq(12L), eq("EXECUTE_NOT_EXECUTED"), eq("a".repeat(64)), eq("b".repeat(64)))).thenReturn(1);
        when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    }

    @Test
    void testnetNotExecutedRecoveryRequiresThreeDistinctOperatorsAndAtomicCas() {
        stubVerifiedAbsence();
        service.execute(1L, 12L, "a".repeat(64), "b".repeat(64));
        verify(executions).markNotExecutedAfterVerifiedAbsence(eq(100L), eq(20L), eq("BTCUSDT"), eq("key-1"), eq("client-1"), anyString(), any());
        verify(positionStates).closeUnexecutedOpenPending(50L, 40L, 7, "key-1", "client-1", "cycle-1", any());
        verify(dispatch).markNotExecuted(20L, "BTCUSDT", "key-1", "client-1", 40L);
        verify(dispatch).release(20L, "BTCUSDT", "key-1");
        verify(capacity).releaseFailed(20L, "key-1");
        verify(manager).commit(any());
    }

    @Test
    void liveCredentialsCannotUseTestnetAbsenceRecovery() {
        when(cases.findCase(1L)).thenReturn(approvedCase());
        QuantCredential credential = new QuantCredential();
        credential.setRunMode("LIVE");
        when(credentials.selectById(20L)).thenReturn(credential);
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 12L, "a".repeat(64), "b".repeat(64)));
        verifyNoInteractions(gateway, dispatch, executions, positionStates, capacity);
    }

    @Test
    void orphanTradePreventsAbsenceRecovery() {
        stubVerifiedAbsence();
        when(gateway.queryTrades(any(), eq("BTCUSDT"), any())).thenReturn(List.of(
            new ExchangeGateway.TradeSnapshot("BTCUSDT", 1L, 999L, "BUY", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, java.time.Instant.now())));
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 12L, "a".repeat(64), "b".repeat(64)));
        verify(dispatch, never()).markNotExecuted(any(), any(), any(), any(), any());
        verify(dispatch, never()).release(any(), any(), any());
        verifyNoInteractions(audit);
        verify(executions, never()).markNotExecutedAfterVerifiedAbsence(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sameApplicantOrReviewerCannotExecuteRecovery() {
        when(cases.findCase(1L)).thenReturn(approvedCase());
        assertThrows(IllegalStateException.class, () -> service.execute(1L, 11L, "a".repeat(64), "b".repeat(64)));
        verifyNoInteractions(gateway, dispatch, executions, positionStates, capacity);
    }
}
