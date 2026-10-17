package org.dromara.quant.market;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.risk.OpenCapacityService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AccountPositionCacheTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final ExchangeGateway.Credential credential = new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET");

    private static ExchangeGateway.PositionSnapshot position(String symbol) {
        return new ExchangeGateway.PositionSnapshot(symbol, "LONG", BigDecimal.ONE, null, null, null, null, null, null, null, null, Instant.ofEpochMilli(30));
    }

    private static ExchangeGateway.UserDataEvent update(String symbol, String side, String quantity, long time) {
        return new ExchangeGateway.UserDataEvent("ACCOUNT_UPDATE", Instant.ofEpochMilli(time), "{\"T\":" + time + ",\"a\":{\"P\":[{\"s\":\"" + symbol + "\",\"ps\":\"" + side +
            "\",\"pa\":\"" + quantity + "\"}]}}");
    }

    @Test
    void initializationIsRequiredAndHealthyReadsNeverQueryRestAgain() throws Exception {
        var cache = new AccountPositionCache(gateway);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
        cache.begin(1L, credential, () -> true);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
        when(gateway.queryPositions(credential, null)).thenReturn(List.of());
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        for (int i = 0; i < 20; i++) {
            assertTrue(cache.requirePositions(1L).isEmpty());
        }
        verify(gateway, times(1)).queryPositions(credential, null);
    }

    @Test
    void incrementalUpdatesPreserveOtherSymbolsAndZeroRemovesOnlyItsPosition() throws Exception {
        var cache = ready();
        cache.onEvent(1L, update("BTCUSDT", "BOTH", "-1", 10));
        cache.onEvent(1L, update("ETHUSDT", "LONG", "2", 11));
        assertEquals(2, cache.requirePositions(1L).size());
        cache.onEvent(1L, update("BTCUSDT", "BOTH", "0", 12));
        assertEquals("ETHUSDT", cache.requirePositions(1L).getFirst().symbol());
        // 旧事件不得把已平仓位置重新写回。
        cache.onEvent(1L, update("BTCUSDT", "BOTH", "-1", 10));
        assertEquals(1, cache.requirePositions(1L).size());
    }

    @Test
    void transportFailureAndMalformedEventsImmediatelyBlockNewEntries() throws Exception {
        var healthy = new AtomicBoolean(true);
        var cache = new AccountPositionCache(gateway);
        when(gateway.queryPositions(credential, null)).thenReturn(List.of());
        cache.begin(1L, credential, healthy::get);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        healthy.set(false);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        cache.onEvent(1L, new ExchangeGateway.UserDataEvent("ACCOUNT_UPDATE", Instant.now(), "{}"));
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
        cache.disconnect(1L);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
    }

    @Test
    void positionArrivingDuringBootstrapSurvivesAnOlderEmptySnapshot() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.queryPositions(credential, null)).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return List.of();
        });
        var cache = new AccountPositionCache(gateway);
        cache.begin(1L, credential, () -> true);
        var load = cache.refresh(1L);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        // 未完成期间的策略读取不能废弃正在初始化的快照。
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
        cache.onEvent(1L, update("PUMPUSDT", "LONG", "10", 20));
        release.countDown();
        load.get(2, TimeUnit.SECONDS);
        assertEquals("PUMPUSDT", cache.requirePositions(1L).getFirst().symbol());
    }

    @Test
    void replacedConnectionCannotBePopulatedByPreviousGenerationSnapshot() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(gateway.queryPositions(credential, null)).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return List.of();
        });
        var cache = new AccountPositionCache(gateway);
        cache.begin(1L, credential, () -> true);
        var oldLoad = cache.refresh(1L);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        cache.disconnect(1L);
        cache.begin(1L, credential, () -> true);
        release.countDown();
        oldLoad.get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
    }

    @Test
    void auditMismatchBlocksUntilFullReinitializationAndAccountsAreIndependent() throws Exception {
        var cache = ready();
        when(gateway.queryPositions(credential, null)).thenReturn(List.of(position("PUMPUSDT")));
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertEquals(1, cache.requirePositions(1L).size());
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(2L));
    }

    @Test
    void failedSnapshotNeverBecomesAnEmptyAccount() throws Exception {
        var cache = new AccountPositionCache(gateway);
        cache.begin(1L, credential, () -> true);
        when(gateway.queryPositions(credential, null)).thenReturn(null);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
    }

    @Test
    void snapshotOlderThanSafetyDeadlineBlocksEvenWithConnectedSocket() throws Exception {
        var cache = ready();
        var accounts = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cache, "accounts");
        org.springframework.test.util.ReflectionTestUtils.setField(accounts.get(1L), "snapshotAt", System.nanoTime() - TimeUnit.SECONDS.toNanos(91));
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
    }

    @Test
    void oldZeroEventCannotRemoveNewerPositionFromBootstrapSnapshot() throws Exception {
        var cache = new AccountPositionCache(gateway);
        when(gateway.queryPositions(credential, null)).thenReturn(List.of(position("PUMPUSDT")));
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        cache.onEvent(1L, update("PUMPUSDT", "LONG", "0", 20));
        assertEquals(1, cache.requirePositions(1L).size());
        cache.onEvent(1L, update("PUMPUSDT", "LONG", "0", 31));
        assertTrue(cache.requirePositions(1L).isEmpty());
    }

    @Test
    void nonzeroSnapshotWithoutExchangeUpdateTimeIsUntrusted() throws Exception {
        var cache = new AccountPositionCache(gateway);
        when(gateway.queryPositions(credential, null)).thenReturn(List.of(new ExchangeGateway.PositionSnapshot("PUMPUSDT", "LONG", BigDecimal.ONE, null, null, null, null, null,
            null, null, null)));
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requirePositions(1L));
    }

    @Test
    void auditWithSameQuantityStillAdvancesExchangeTimeBarrier() throws Exception {
        var cache = new AccountPositionCache(gateway);
        when(gateway.queryPositions(credential, null)).thenReturn(List.of(position("PUMPUSDT")));
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        when(gateway.queryPositions(credential, null)).thenReturn(List.of(new ExchangeGateway.PositionSnapshot("PUMPUSDT", "LONG", BigDecimal.ONE, null, null, null, null, null,
            null, null, null, Instant.ofEpochMilli(40))));
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        cache.onEvent(1L, update("PUMPUSDT", "LONG", "0", 35));
        assertEquals(1, cache.requirePositions(1L).size());
    }

    private AccountPositionCache ready() throws Exception {
        var cache = new AccountPositionCache(gateway);
        cache.begin(1L, credential, () -> true);
        when(gateway.queryPositions(credential, null)).thenReturn(List.of());
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        return cache;
    }
}
