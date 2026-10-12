package org.dromara.quant.protection;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.protection.domain.QuantProtectionOrder;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Tag("dev")
class ProtectionOrderServiceTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final QuantProtectionOrderMapper mapper = mock(QuantProtectionOrderMapper.class);
    private final TradeExecutionApplicationService execution = mock(TradeExecutionApplicationService.class);
    private final ProtectionOrderService service = new ProtectionOrderService(gateway, mapper, execution);
    private final QuantPosition position = new QuantPosition();
    private final QuantCredential credential = new QuantCredential();

    @BeforeEach
    void setup() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "test"), QuantProtectionOrder.class);
        position.setId(10L);
        position.setSymbol("BTCUSDT");
        position.setPositionSide("LONG");
        position.setEntryPrice(new BigDecimal("100"));
        position.setQuantity(BigDecimal.ONE);
        position.setMargin(new BigDecimal("10"));
        position.setLeverage(10);
        credential.setId(1L);
        var symbol = new org.dromara.quant.market.domain.QuantSymbol();
        when(execution.requireSymbol("BTCUSDT")).thenReturn(symbol);
        when(execution.symbolRule(symbol)).thenReturn(new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 3, new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("0.001"), BigDecimal.ONE, "TRADING"));
        when(execution.requireCredential(1L)).thenReturn(credential);
        when(execution.toCredential(credential)).thenReturn(new ExchangeGateway.Credential("test", "test", "HMAC", "LIVE"));
        when(gateway.queryTradingRule(any(), any())).thenReturn(new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 3, new BigDecimal("0.01"), new BigDecimal("0.001"), new BigDecimal("0.001"), BigDecimal.ONE, "TRADING"));
        when(gateway.queryPositionMode(any())).thenReturn(new ExchangeGateway.PositionMode(false));
        doAnswer(invocation -> { invocation.<QuantProtectionOrder>getArgument(0).setId(8L); return 1; }).when(mapper).insert(any(QuantProtectionOrder.class));
        when(mapper.updateById(any(QuantProtectionOrder.class))).thenReturn(1);
    }

    @Test
    void uncertainExchangeStateMustNotBeReportedAsWorking() {
        QuantProtectionOrder local = order();
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenThrow(new IllegalStateException("timeout"));
        assertFalse(service.isStrategyProtectionWorking(1L, position, "FIRST_TP", true));
    }

    @Test
    void intentIsDurableBeforeSendingProtectionOrder() {
        when(gateway.placeProtectionOrder(any())).thenAnswer(invocation -> {
            verify(mapper).insert(any(QuantProtectionOrder.class));
            return new ExchangeGateway.ProtectionOrderResult(55L, invocation.<ExchangeGateway.ProtectionOrderRequest>getArgument(0).clientOrderId(), "NEW");
        });
        doAnswer(invocation -> {
            invocation.<QuantProtectionOrder>getArgument(0).setId(7L);
            return 1;
        }).when(mapper).insert(any(QuantProtectionOrder.class));
        // PAPER bypasses symbol rules; persistence ordering is shared with LIVE.
        service.placeMarginHardStop(1L, position, new BigDecimal("0.1"), false);
    }

    @Test
    void timeoutKeepsSameIntentForRecoveryInsteadOfCreatingAnotherOrder() {
        QuantProtectionOrder intent = order();
        intent.setStatus("UNKNOWN");
        intent.setExchangeOrderId(null);
        when(mapper.selectOne(any())).thenReturn(intent);
        when(gateway.queryProtectionOrders(any(), any())).thenReturn(List.of());
        assertThrows(IllegalStateException.class, () -> service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        verify(gateway, never()).placeProtectionOrder(any());
    }

    @Test
    void replacementIsConfirmedBeforeOldOrderIsCanceled() {
        QuantProtectionOrder old = order(); old.setQuantity(new BigDecimal("2"));
        when(mapper.selectOne(any())).thenReturn(old);
        when(gateway.queryProtectionOrder(any(), eq(55L), any(), any())).thenReturn(snapshot(55L, old.getClientOrderId(), new BigDecimal("2"), "NEW", null),
            snapshot(55L, old.getClientOrderId(), new BigDecimal("2"), "CANCELED", BigDecimal.ZERO));
        final ExchangeGateway.ProtectionOrderRequest[] request = new ExchangeGateway.ProtectionOrderRequest[1];
        when(gateway.placeProtectionOrder(any())).thenAnswer(invocation -> {
            request[0] = invocation.getArgument(0);
            return new ExchangeGateway.ProtectionOrderResult(66L, request[0].clientOrderId(), "NEW");
        });
        when(gateway.queryProtectionOrder(any(), eq(66L), any(), any())).thenAnswer(invocation -> snapshot(66L, request[0].clientOrderId(), BigDecimal.ONE, "NEW", null));
        service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true);
        var sequence = inOrder(gateway);
        sequence.verify(gateway).queryProtectionOrder(any(), eq(55L), any(), any());
        sequence.verify(gateway).placeProtectionOrder(any());
        sequence.verify(gateway).queryProtectionOrder(any(), eq(66L), any(), any());
        sequence.verify(gateway).cancelProtectionOrder(any());
    }

    @Test
    void unknownAcceptedOrderIsRecoveredWithoutResubmission() {
        QuantProtectionOrder local = order(); local.setStatus("UNKNOWN");
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, local.getClientOrderId(), BigDecimal.ONE, "NEW", null));
        assertSame(local, service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        assertEquals("WORKING", local.getStatus());
        verify(gateway, never()).placeProtectionOrder(any());
    }

    @Test
    void explicitlyAbsentIntentRetriesThePersistedClientId() {
        QuantProtectionOrder local = order(); local.setStatus("UNKNOWN"); local.setExchangeOrderId(null);
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any()))
            .thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ProtectionOrderAbsentException())
            .thenReturn(snapshot(55L, local.getClientOrderId(), BigDecimal.ONE, "NEW", null));
        when(gateway.placeProtectionOrder(any())).thenReturn(new ExchangeGateway.ProtectionOrderResult(55L, local.getClientOrderId(), "NEW"));
        service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true);
        verify(gateway).placeProtectionOrder(argThat(request -> local.getClientOrderId().equals(request.clientOrderId())));
        verify(mapper, never()).insert(any(QuantProtectionOrder.class));
    }

    @Test
    void canceledFirstTpAndManualReductionDoNotConfirmTakeProfit() {
        when(mapper.selectOne(any())).thenReturn(order());
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "QFTP_test", BigDecimal.ONE, "CANCELED", BigDecimal.ZERO));
        position.setQuantity(new BigDecimal("0.5"));
        assertFalse(service.isFirstTakeProfitFilled(1L, position, BigDecimal.ONE));
    }

    @Test
    void onlyFinishedProtectionWithRealExecutedQuantityConfirmsTakeProfit() {
        QuantProtectionOrder target = order(); target.setQuantity(new BigDecimal("0.5"));
        when(mapper.selectOne(any())).thenReturn(target);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "QFTP_test", new BigDecimal("0.5"), "FINISHED", new BigDecimal("0.5")));
        position.setQuantity(new BigDecimal("0.5"));
        assertTrue(service.isFirstTakeProfitFilled(1L, position, BigDecimal.ONE));
    }

    @Test
    void triggeringFirstTpMustBlockLocalFallback() {
        QuantProtectionOrder local = order(); local.setProtectionType("FIRST_TP");
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "QFTP_test", BigDecimal.ONE, "TRIGGERING", null));
        assertThrows(IllegalStateException.class, () -> service.isStrategyProtectionWorking(1L, position, "FIRST_TP", true));
    }

    @Test
    void failedCancellationAndEmptyOpenListCannotAuthorizePartialFallback() {
        when(mapper.selectOne(any())).thenReturn(order());
        doThrow(new IllegalStateException("already triggered")).when(gateway).cancelProtectionOrder(any());
        when(gateway.queryProtectionOrders(any(), any())).thenReturn(List.of());
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "QFTP_test", BigDecimal.ONE, "FINISHED", new BigDecimal("0.5")));
        assertFalse(service.cancelStrategyProtection(1L, position, "FIRST_TP"));
    }

    @Test
    void finishedFirstTpWaitsUntilPositionReflectsExecutedQuantity() {
        when(mapper.selectOne(any())).thenReturn(order());
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "QFTP_test", new BigDecimal("0.5"), "FINISHED", new BigDecimal("0.5")));
        assertFalse(service.isFirstTakeProfitFilled(1L, position, BigDecimal.ONE));
    }

    @Test
    void fixedHardStopDoesNotMoveWithMarginFluctuation() {
        QuantProtectionOrder local = order(); local.setPositionSide("LONG");
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, local.getClientOrderId(), BigDecimal.ONE, "NEW", null));
        position.setMargin(new BigDecimal("12"));
        assertSame(local, service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        verify(gateway, never()).placeProtectionOrder(any());
        verify(gateway, never()).cancelProtectionOrder(any());
    }

    @Test
    void stepStopNeverMovesBackwards() {
        QuantProtectionOrder local = order(); local.setPositionSide("LONG"); local.setProtectionType("STEP_TP");
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, local.getClientOrderId(), BigDecimal.ONE, "NEW", null));
        service.syncStrategyProtections(1L, position, BigDecimal.ONE, List.of(new org.dromara.quant.strategy.ProtectionSignal(
            "STEP_TP", "STOP_MARKET", BigDecimal.ONE, org.dromara.quant.strategy.ProtectionSignal.QuantityBase.CURRENT, new BigDecimal("98"), "test")), true);
        verify(gateway, never()).placeProtectionOrder(any());
        verify(gateway, never()).cancelProtectionOrder(any());
    }

    @Test
    void shortStepStopNeverMovesBackwards() {
        position.setPositionSide("SHORT");
        QuantProtectionOrder local = order(); local.setPositionSide("SHORT"); local.setProtectionType("STEP_TP");
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(new ExchangeGateway.ProtectionOrderSnapshot(
            "BTCUSDT", 55L, local.getClientOrderId(), "NEW", "BUY", "BOTH", "STOP_MARKET", BigDecimal.ONE, new BigDecimal("99"), Instant.now(), null));
        service.syncStrategyProtections(1L, position, BigDecimal.ONE, List.of(new org.dromara.quant.strategy.ProtectionSignal(
            "STEP_TP", "STOP_MARKET", BigDecimal.ONE, org.dromara.quant.strategy.ProtectionSignal.QuantityBase.CURRENT, new BigDecimal("100"), "test")), true);
        verify(gateway, never()).placeProtectionOrder(any());
        verify(gateway, never()).cancelProtectionOrder(any());
    }

    @Test
    void explicitlyAbsentFirstTpDoesNotAbortPositionRecovery() {
        when(mapper.selectOne(any())).thenReturn(order());
        when(gateway.queryProtectionOrder(any(), any(), any(), any()))
            .thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ProtectionOrderAbsentException());
        assertFalse(service.isFirstTakeProfitFilled(1L, position, BigDecimal.ONE));
        verify(gateway, never()).placeProtectionOrder(any());
    }

    @Test
    void protectionUsesActualEnvironmentRuleInsteadOfLocalSymbolRule() {
        when(gateway.queryTradingRule(any(), any())).thenReturn(new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0,
            new BigDecimal("0.1"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING"));
        position.setQuantity(new BigDecimal("11233"));
        when(gateway.placeProtectionOrder(any())).thenAnswer(i -> new ExchangeGateway.ProtectionOrderResult(55L,
            i.<ExchangeGateway.ProtectionOrderRequest>getArgument(0).clientOrderId(), "NEW"));
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenAnswer(i -> snapshot(55L, i.getArgument(2),
            new BigDecimal("5616"), "NEW", null));
        service.syncStrategyProtections(1L, position, position.getQuantity(), List.of(new org.dromara.quant.strategy.ProtectionSignal(
            "FIRST_TP", "STOP_MARKET", new BigDecimal("0.5"), org.dromara.quant.strategy.ProtectionSignal.QuantityBase.INITIAL,
            new BigDecimal("99"), "test")), true);
        verify(gateway).placeProtectionOrder(argThat(r -> r.quantity().compareTo(new BigDecimal("5616")) == 0));
        verify(execution, never()).requireSymbol(any());
    }

    @Test
    void absentRetryPersistsNormalizedQuantityBeforeResendingSameId() {
        QuantProtectionOrder local = order(); local.setStatus("UNKNOWN"); local.setQuantity(new BigDecimal("1.5"));
        local.setPositionSide("LONG"); local.setExchangeOrderId(null); position.setQuantity(new BigDecimal("2"));
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryTradingRule(any(), any())).thenReturn(new ExchangeGateway.SymbolRule("BTCUSDT", "USDT", 2, 0,
            new BigDecimal("0.01"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "TRADING"));
        when(mapper.updateById(any(QuantProtectionOrder.class))).thenReturn(1);
        when(gateway.queryProtectionOrder(any(), any(), any(), any()))
            .thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ProtectionOrderAbsentException())
            .thenReturn(snapshot(55L, local.getClientOrderId(), BigDecimal.ONE, "NEW", null));
        when(gateway.placeProtectionOrder(any())).thenAnswer(i -> {
            assertEquals(0, local.getQuantity().compareTo(BigDecimal.ONE));
            verify(mapper, atLeastOnce()).updateById(local);
            throw new IllegalStateException("timeout after submit");
        });
        assertThrows(IllegalStateException.class, () -> service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        verify(gateway).placeProtectionOrder(argThat(r -> r.clientOrderId().equals(local.getClientOrderId())
            && r.quantity().compareTo(BigDecimal.ONE) == 0));
        assertEquals("UNKNOWN", local.getStatus());
    }

    @Test
    void definiteRejectionIsNotRecordedAsUnknown() {
        when(gateway.placeProtectionOrder(any())).thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.OrderRejectedException(-4023, "step"));
        assertThrows(IllegalStateException.class, () -> service.placeMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        verify(mapper).updateById(argThat((QuantProtectionOrder o) -> "REJECTED".equals(o.getStatus())));
    }

    @Test
    void duplicateClientIdMustConfirmOriginalIntentWithoutAnotherSubmission() {
        when(gateway.placeProtectionOrder(any())).thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
            "PLACE_PROTECTION_ORDER", 400, -4116, "ClientOrderId is duplicated"));
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenAnswer(invocation ->
            snapshot(55L, invocation.getArgument(2), BigDecimal.ONE, "NEW", null));
        var result = service.placeMarginHardStop(1L, position, new BigDecimal("0.1"), true);
        assertEquals("WORKING", result.getStatus());
        assertEquals(55L, result.getExchangeOrderId());
        verify(gateway, times(1)).placeProtectionOrder(any());
        verify(mapper, times(1)).insert(any(QuantProtectionOrder.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void duplicateSubmissionWithUnconfirmedFieldsMustRemainUnknown(boolean queryFails) {
        when(gateway.placeProtectionOrder(any())).thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
            "PLACE_PROTECTION_ORDER", 400, -4116, "ClientOrderId is duplicated"));
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (queryFails) throw new IllegalStateException("query timeout");
            return snapshot(55L, invocation.getArgument(2), new BigDecimal("2"), "NEW", null);
        });
        assertThrows(IllegalStateException.class, () -> service.placeMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        verify(mapper, atLeastOnce()).updateById(argThat((QuantProtectionOrder o) -> "UNKNOWN".equals(o.getStatus())));
        verify(gateway, times(1)).placeProtectionOrder(any());
    }

    @Test
    void recoveredIntentDuplicateSubmissionMustQuerySameClientId() {
        var local = order();
        local.setStatus("UNKNOWN"); local.setExchangeOrderId(null);
        when(mapper.selectOne(any())).thenReturn(local);
        when(gateway.queryProtectionOrder(any(), any(), any(), any()))
            .thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ProtectionOrderAbsentException())
            .thenReturn(snapshot(55L, local.getClientOrderId(), BigDecimal.ONE, "NEW", null));
        when(gateway.placeProtectionOrder(any())).thenThrow(new org.dromara.quant.exchange.ExchangeTradingPort.ExchangeRequestException(
            "PLACE_PROTECTION_ORDER", 400, -4116, "ClientOrderId is duplicated"));
        assertEquals(local, service.ensureMarginHardStop(1L, position, new BigDecimal("0.1"), true));
        assertEquals("WORKING", local.getStatus());
        verify(gateway, times(1)).placeProtectionOrder(argThat(r -> r.clientOrderId().equals(local.getClientOrderId())));
        verify(mapper, never()).insert(any(QuantProtectionOrder.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"TESTNET", "LIVE"})
    void hardStopUsesSameCalculationAndActualEnvironment(String mode) {
        position.setMargin(new BigDecimal("30")); // 追加保证金不改变冻结杠杆回报率触发价。
        when(execution.toCredential(credential)).thenReturn(new ExchangeGateway.Credential("test", "test", "HMAC", mode));
        when(gateway.placeProtectionOrder(any())).thenAnswer(invocation -> new ExchangeGateway.ProtectionOrderResult(
            55L, invocation.<ExchangeGateway.ProtectionOrderRequest>getArgument(0).clientOrderId(), "NEW"));
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenAnswer(invocation ->
            snapshot(55L, invocation.getArgument(2), BigDecimal.ONE, "NEW", null));
        var result = service.placeMarginHardStop(1L, position, new BigDecimal("0.1"), true);
        assertEquals("WORKING", result.getStatus());
        verify(gateway).placeProtectionOrder(argThat(r -> mode.equals(r.credential().runMode()) && r.live()
            && r.quantity().compareTo(BigDecimal.ONE) == 0 && r.stopPrice().compareTo(new BigDecimal("99")) == 0));
    }

    @Test
    void partialFinishedFirstProfitCannotEnableStep() {
        when(mapper.selectOne(any())).thenReturn(order());
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "QFTP_test", BigDecimal.ONE, "FINISHED", new BigDecimal("0.2")));
        position.setQuantity(new BigDecimal("0.8"));
        assertFalse(service.isFirstTakeProfitFilled(1L, position, BigDecimal.ONE));
    }

    @Test
    void firstProfitProgressAccumulatesDistinctOrdersButNotRepeatedQueries() {
        position.setProtectionCycleKey("cycle");
        QuantProtectionOrder one = order(); one.setExecutedQuantity(BigDecimal.ZERO);
        QuantProtectionOrder two = order(); two.setExchangeOrderId(56L); two.setExecutedQuantity(BigDecimal.ZERO);
        when(mapper.selectList(any())).thenReturn(List.of(one, two));
        when(gateway.queryProtectionOrder(any(), eq(55L), any(), any())).thenReturn(snapshot(55L, "one", BigDecimal.ONE, "CANCELED", new BigDecimal("0.2")));
        when(gateway.queryProtectionOrder(any(), eq(56L), any(), any())).thenReturn(snapshot(56L, "two", BigDecimal.ONE, "FINISHED", new BigDecimal("0.3")));
        assertEquals(0, new BigDecimal("0.5").compareTo(service.firstTakeProfitExecutedQuantity(1L, position)));
        assertEquals(0, new BigDecimal("0.5").compareTo(service.firstTakeProfitExecutedQuantity(1L, position)));
    }

    @Test
    void cancellationTimeoutCannotAuthorizeLocalClose() {
        position.setProtectionCycleKey("cycle");
        when(mapper.selectList(any())).thenReturn(List.of(order()));
        doThrow(new IllegalStateException("timeout")).when(gateway).cancelProtectionOrder(any());
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "one", BigDecimal.ONE, "NEW", BigDecimal.ZERO));
        assertFalse(service.quiesceCycleProtections(1L, position));
    }

    @Test
    void terminalPartialFillIsPreservedBeforeLocalSupplement() {
        position.setProtectionCycleKey("cycle");
        QuantProtectionOrder one = order();
        when(mapper.selectList(any())).thenReturn(List.of(one));
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(snapshot(55L, "one", BigDecimal.ONE, "CANCELED", new BigDecimal("0.2")));
        assertTrue(service.quiesceCycleProtections(1L, position));
        assertEquals(0, new BigDecimal("0.2").compareTo(one.getExecutedQuantity()));
    }

    @Test
    void finishedAlgoWithActiveChildOrderMustNotAuthorizeLocalFallback() {
        position.setProtectionCycleKey("cycle");
        when(mapper.selectList(any())).thenReturn(List.of(order()));
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(new ExchangeGateway.ProtectionOrderSnapshot("BTCUSDT", 55L, "one",
            "FINISHED", "SELL", "BOTH", "STOP_MARKET", BigDecimal.ONE, new BigDecimal("99"), Instant.now(), BigDecimal.ZERO, true, false, 99L, null));
        when(gateway.queryOrder(any(), any(), eq(99L), any())).thenReturn(new ExchangeGateway.OrderSnapshot("BTCUSDT", 99L, "child", "SELL", "MARKET", "NEW",
            BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, Instant.now()));
        assertThrows(ProtectionOrderService.ProtectionExecutionPendingException.class, () -> service.quiesceCycleProtections(1L, position));
    }

    @Test
    void unknownCancellationAndAbsentOpenListMustRetainCleanup() {
        when(mapper.selectList(any())).thenReturn(List.of(order()));
        doThrow(new IllegalStateException("timeout")).when(gateway).cancelProtectionOrder(any());
        when(gateway.queryProtectionOrders(any(), any())).thenReturn(List.of());
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenThrow(new IllegalStateException("timeout"));
        assertFalse(service.cancelActiveProtectionOrders(1L, position.getId(), "cycle", true));
        verify(gateway, never()).queryProtectionOrders(any(), any());
    }

    @Test
    void firstProfitReplacementWaitsForOldFillReconciliation() {
        QuantProtectionOrder old = order(); old.setProtectionType("FIRST_TP"); old.setPositionSide("LONG");
        when(mapper.selectOne(any())).thenReturn(old);
        when(gateway.queryProtectionOrder(any(), any(), any(), any())).thenReturn(
            snapshot(55L, old.getClientOrderId(), BigDecimal.ONE, "NEW", BigDecimal.ZERO),
            snapshot(55L, old.getClientOrderId(), BigDecimal.ONE, "CANCELED", new BigDecimal("0.2")));
        assertThrows(ProtectionOrderService.ProtectionExecutionPendingException.class, () -> service.syncStrategyProtections(1L, position, BigDecimal.ONE,
            List.of(new org.dromara.quant.strategy.ProtectionSignal("FIRST_TP", "STOP_MARKET", new BigDecimal("0.5"),
                org.dromara.quant.strategy.ProtectionSignal.QuantityBase.INITIAL, new BigDecimal("99"), "test")), true));
        verify(gateway, never()).placeProtectionOrder(any());
        assertEquals(0, new BigDecimal("0.2").compareTo(old.getExecutedQuantity()));
    }

    private ExchangeGateway.ProtectionOrderSnapshot snapshot(Long id, String clientId, BigDecimal qty, String status, BigDecimal filled) {
        return new ExchangeGateway.ProtectionOrderSnapshot("BTCUSDT", id, clientId, status, "SELL", "BOTH", "STOP_MARKET", qty, new BigDecimal("99"), Instant.now(), filled);
    }

    private QuantProtectionOrder order() {
        QuantProtectionOrder order = new QuantProtectionOrder();
        order.setId(7L);
        order.setPositionId(10L);
        order.setSymbol("BTCUSDT");
        order.setClientOrderId("QFTP_test");
        order.setExchangeOrderId(55L);
        order.setQuantity(BigDecimal.ONE);
        order.setStopPrice(new BigDecimal("99"));
        order.setOrderType("STOP_MARKET");
        order.setStatus("WORKING");
        return order;
    }
}
