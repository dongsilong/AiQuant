package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.order.domain.QuantOrder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;

import static org.dromara.quant.execution.StrategyPositionAccounting.PositionSide;
import static org.dromara.quant.execution.StrategyPositionAccounting.Role;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@Tag("dev")
class StrategyPositionProjectionValidationTest {
    private final StrategyPositionProjectionMapper mapper = mock(StrategyPositionProjectionMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final StrategyPositionProjectionService service = new StrategyPositionProjectionService(mapper, manager);

    private QuantOrder order() {
        var o = new QuantOrder();
        o.setCredentialId(5L);
        o.setStrategyId(7L);
        o.setStrategyInstanceId(8L);
        o.setSymbol("BTCUSDT");
        o.setExchangeOrderId(9L);
        o.setPositionSide("LONG");
        o.setSide("BUY");
        o.setReduceOnly(false);
        return o;
    }

    private ExchangeGateway.TradeSnapshot fill() {
        return new ExchangeGateway.TradeSnapshot("BTCUSDT", 11L, 9L, "BUY", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, Instant.parse("2026-10-14T02:00:00Z"
        ));
    }

    @Test
    void exclusiveInventoryRequiresInstanceAndAggregateToEqualExchangeExposure() {
        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("instance_strategy_id", 7L);
        snapshot.put("instance_quantity", new BigDecimal("1.25"));
        snapshot.put("aggregate_quantity", new BigDecimal("1.25"));
        snapshot.put("instance_cycle_id", 100L);
        snapshot.put("instance_cycle_number", 1L);
        org.mockito.Mockito.when(mapper.ownershipSnapshot(5L, "BTCUSDT", 8L, "LONG")).thenReturn(snapshot);

        assertTrue(service.matchesExclusiveInventory(5L, "BTCUSDT", 7L, 8L, "LONG", new BigDecimal("1.25")));
        assertFalse(service.matchesExclusiveInventory(5L, "BTCUSDT", 7L, 8L, "LONG", new BigDecimal("1.20")));
        assertFalse(service.matchesExclusiveInventory(5L, "BTCUSDT", 9L, 8L, "LONG", new BigDecimal("1.25")));
        assertFalse(service.matchesExclusiveInventory(5L, "BTCUSDT", 7L, 8L, "SHORT", new BigDecimal("1.25")));
    }

    @Test
    void flatAccountExposureRequiresZeroInventoryForTheRequestedSide() {
        var empty = new java.util.HashMap<String, Object>();
        empty.put("instance_quantity", BigDecimal.ZERO);
        empty.put("aggregate_quantity", BigDecimal.ZERO);
        org.mockito.Mockito.when(mapper.ownershipSnapshot(5L, "BTCUSDT", 8L, "LONG")).thenReturn(empty);
        assertTrue(service.matchesExclusiveInventory(5L, "BTCUSDT", 7L, 8L, "LONG", BigDecimal.ZERO));

        var residual = new java.util.HashMap<String, Object>();
        residual.put("instance_strategy_id", 7L);
        residual.put("instance_quantity", new BigDecimal("0.25"));
        residual.put("aggregate_quantity", new BigDecimal("0.25"));
        residual.put("instance_cycle_id", 100L);
        residual.put("instance_cycle_number", 1L);
        org.mockito.Mockito.when(mapper.ownershipSnapshot(5L, "BTCUSDT", 8L, "SHORT")).thenReturn(residual);
        assertFalse(service.matchesExclusiveInventory(5L, "BTCUSDT", 7L, 8L, "SHORT", BigDecimal.ZERO));
    }

    @Test
    void sharedAccountExposureIsNotAssignedToOneStrategyInstance() {
        var snapshot = new java.util.HashMap<String, Object>();
        snapshot.put("instance_strategy_id", 7L);
        snapshot.put("instance_quantity", new BigDecimal("0.5"));
        snapshot.put("aggregate_quantity", new BigDecimal("1.0"));
        snapshot.put("instance_cycle_id", 100L);
        snapshot.put("instance_cycle_number", 1L);
        org.mockito.Mockito.when(mapper.ownershipSnapshot(5L, "BTCUSDT", 8L, "LONG")).thenReturn(snapshot);

        assertFalse(service.matchesExclusiveInventory(5L, "BTCUSDT", 7L, 8L, "LONG", BigDecimal.ONE));
    }

    @Test
    void rejectsFillWithoutImmutableAcceptedOrderIntent() {
        org.mockito.Mockito.when(mapper.verifiedIntent(5L, "BTCUSDT", 9L)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> service.projectAttributed(5L, order(), fill()));
        verifyNoInteractions(manager);
        org.mockito.Mockito.verify(mapper).verifiedIntent(5L, "BTCUSDT", 9L);
    }

    @Test
    void rejectsAmbiguousCloseWithoutAnyDatabaseMutation() {
        var o = order();
        assertThrows(IllegalStateException.class, () -> service.project(5L, o, fill(), PositionSide.LONG, Role.CLOSE));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsMalformedExchangeFinancialFactsBeforeDatabaseAccess() {
        var o = order();
        var malformed = new ExchangeGateway.TradeSnapshot("BTCUSDT", 11L, 9L, "BUY", null, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            Instant.parse("2026-10-14T02:00:00Z"));
        assertThrows(IllegalStateException.class, () -> service.project(5L, o, malformed, PositionSide.LONG, Role.OPEN));
        var unknownSide = new ExchangeGateway.TradeSnapshot("BTCUSDT", 11L, 9L, "UNKNOWN", BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO,
            Instant.parse("2026-10-14T02:00:00Z"));
        assertThrows(IllegalStateException.class, () -> service.project(5L, o, unknownSide, PositionSide.LONG, Role.OPEN));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsForeignTradeAndMissingHedgeSide() {
        var o = order();
        o.setExchangeOrderId(999L);
        assertThrows(IllegalStateException.class, () -> service.project(5L, o, fill(), PositionSide.LONG, Role.OPEN));
        o.setExchangeOrderId(9L);
        o.setPositionSide("BOTH");
        assertThrows(IllegalStateException.class, () -> service.project(5L, o, fill(), PositionSide.LONG, Role.OPEN));
        verifyNoInteractions(mapper);
    }
}
