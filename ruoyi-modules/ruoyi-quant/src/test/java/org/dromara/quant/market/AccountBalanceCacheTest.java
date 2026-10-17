package org.dromara.quant.market;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.risk.OpenCapacityService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class AccountBalanceCacheTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final OpenCapacityService capacity = mock(OpenCapacityService.class);
    private final ExchangeGateway.Credential credential = new ExchangeGateway.Credential("test", "test", "HMAC", "TESTNET");

    private static ExchangeGateway.AccountSnapshot account() {
        return new ExchangeGateway.AccountSnapshot(new BigDecimal("200"), new BigDecimal("100"), new BigDecimal("200"), BigDecimal.ZERO);
    }

    @Test
    void congestionNeverExtendsExpiredSnapshot() throws Exception {
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenReturn(account());
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        var accounts = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cache, "accounts");
        org.springframework.test.util.ReflectionTestUtils.setField(accounts.get(1L), "snapshot", new AccountBalanceSnapshot(1L, BigDecimal.TEN, Set.of(), System.nanoTime() - 1));
        org.springframework.test.util.ReflectionTestUtils.setField(accounts.get(1L), "retryAt", 0L);
        when(gateway.queryAccountFresh(credential)).thenThrow(new org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException());
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
    }

    @Test
    void reconnectRequiresNewCalibrationAndThenRestoresEntryGate() throws Exception {
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenReturn(account());
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        cache.disconnect(1L);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
        cache.onEvent(1L, new ExchangeGateway.UserDataEvent("CONNECTED", Instant.now(), "{}"));
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("100"), cache.requireSnapshot(1L).availableBalance());
        verify(gateway, times(2)).queryAccountFresh(credential);
    }

    @Test
    void localCongestionKeepsOnlyUnexpiredUninvalidatedBalance() throws Exception {
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenReturn(account());
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        var before = cache.requireSnapshot(1L);
        var accounts = (java.util.Map<?, ?>) org.springframework.test.util.ReflectionTestUtils.getField(cache, "accounts");
        org.springframework.test.util.ReflectionTestUtils.setField(accounts.get(1L), "retryAt", 0L);
        when(gateway.queryAccountFresh(credential)).thenThrow(new org.dromara.quant.exchange.ExchangeMarketPort.RequestDeferredException());
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertSame(before, cache.requireSnapshot(1L));
        cache.onEvent(1L, new ExchangeGateway.UserDataEvent("ACCOUNT_UPDATE", Instant.now(), "{}"));
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
    }

    @Test
    void slowRestRunsInBackgroundAndAllSymbolsShareOneQuery() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of("filled-before-query"));
        when(gateway.queryAccountFresh(credential)).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return account();
        });
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        var future = cache.refresh(1L);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertSame(future, cache.refresh(1L));
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
        release.countDown();
        future.get(2, TimeUnit.SECONDS);
        var snapshot = cache.requireSnapshot(1L);
        assertEquals(new BigDecimal("100"), snapshot.availableBalance());
        assertEquals(Set.of("filled-before-query"), snapshot.coveredExecutions());
        for (int i = 0; i < 20; i++) {
            assertSame(snapshot, cache.requireSnapshot(1L));
        }
        verify(gateway, times(1)).queryAccountFresh(credential);
    }

    @Test
    void accountUpdateDuringQueryCannotPublishOldBalance() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return account();
        });
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        var future = cache.refresh(1L);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        cache.onEvent(1L, new ExchangeGateway.UserDataEvent("ACCOUNT_UPDATE", Instant.now(), "{}"));
        release.countDown();
        future.get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
    }

    @Test
    void disconnectedOrUnhealthyAccountImmediatelyBlocksEntries() throws Exception {
        var healthy = new AtomicBoolean(true);
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenReturn(account());
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, healthy::get);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        healthy.set(false);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
        cache.disconnect(1L);
        healthy.set(true);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
        verify(gateway, times(1)).queryAccountFresh(credential);
    }

    @Test
    void oldConnectionResultCannotPopulateReplacement() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenAnswer(call -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return account();
        });
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        var future = cache.refresh(1L);
        assertTrue(started.await(1, TimeUnit.SECONDS));
        cache.disconnect(1L);
        cache.begin(1L, credential, () -> true);
        cache.disconnect(1L);
        release.countDown();
        future.get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
    }

    @Test
    void missingAvailableBalanceIsNeverReplacedWithWalletBalance() throws Exception {
        when(capacity.balanceCoverage(1L)).thenReturn(Set.of());
        when(gateway.queryAccountFresh(credential)).thenReturn(new ExchangeGateway.AccountSnapshot(BigDecimal.TEN, null, BigDecimal.TEN, BigDecimal.ZERO));
        var cache = new AccountBalanceCache(gateway, capacity);
        cache.begin(1L, credential, () -> true);
        cache.refresh(1L).get(2, TimeUnit.SECONDS);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> cache.requireSnapshot(1L));
    }
}
