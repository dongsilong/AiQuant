package org.dromara.quant.exchange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountPositionSnapshotQueryTest {
    private final ExchangeGateway gateway = mock(ExchangeGateway.class);
    private final ExchangeGateway.Credential credential = new ExchangeGateway.Credential("key", "secret", "HMAC", "TESTNET");

    @Test
    void concurrentQueriesForSameCredentialShareOneExchangeRequest() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var snapshots = List.of(new ExchangeGateway.PositionSnapshot(
            "BTCUSDT", "LONG", java.math.BigDecimal.ONE, null, null, null, null, null, null, null, null));
        when(gateway.queryPositions(credential, null)).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(release.await(2, TimeUnit.SECONDS));
            return snapshots;
        });
        var query = new AccountPositionSnapshotQuery(gateway);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> query.query(10L, credential));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            var secondStarted = new CountDownLatch(1);
            var secondThread = new AtomicReference<Thread>();
            var second = executor.submit(() -> {
                secondThread.set(Thread.currentThread());
                secondStarted.countDown();
                return query.query(10L, credential);
            });
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (secondThread.get().getState() != Thread.State.WAITING && System.nanoTime() < waitDeadline) {
                Thread.onSpinWait();
            }
            assertEquals(Thread.State.WAITING, secondThread.get().getState());
            release.countDown();
            assertSame(snapshots.getFirst(), first.get(2, TimeUnit.SECONDS).getFirst());
            assertSame(snapshots.getFirst(), second.get(2, TimeUnit.SECONDS).getFirst());
        }
        verify(gateway, times(1)).queryPositions(credential, null);
    }

    @Test
    void failedRequestIsSharedAndNextCallCanRetry() {
        var expected = new IllegalStateException("deferred");
        when(gateway.queryPositions(credential, null)).thenThrow(expected).thenReturn(List.of());
        var query = new AccountPositionSnapshotQuery(gateway);

        assertSame(expected, assertThrows(IllegalStateException.class, () -> query.query(10L, credential)));
        assertTrue(query.query(10L, credential).isEmpty());
        verify(gateway, times(2)).queryPositions(credential, null);
    }

    @Test
    void differentCredentialsDoNotShareRequests() {
        var other = new ExchangeGateway.Credential("other", "secret", "HMAC", "TESTNET");
        when(gateway.queryPositions(any(), isNull())).thenReturn(List.of());

        var query = new AccountPositionSnapshotQuery(gateway);
        query.query(10L, credential);
        query.query(11L, other);

        verify(gateway, times(1)).queryPositions(credential, null);
        verify(gateway, times(1)).queryPositions(other, null);
    }
}
