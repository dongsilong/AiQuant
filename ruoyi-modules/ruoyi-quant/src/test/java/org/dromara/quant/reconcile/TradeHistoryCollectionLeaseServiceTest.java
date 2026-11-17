package org.dromara.quant.reconcile;

import org.dromara.quant.reconcile.mapper.TradeHistoryCollectionLeaseMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TradeHistoryCollectionLeaseServiceTest {
    @Test
    void acquiresRenewsFencesAndReleasesTheSameEpoch() {
        var mapper = mock(TradeHistoryCollectionLeaseMapper.class);
        when(mapper.acquire(eq(9L), eq("BTCUSDT"), anyString(), eq(90))).thenReturn(1);
        when(mapper.currentEpoch(eq(9L), eq("BTCUSDT"), anyString())).thenReturn(17L);
        when(mapper.renew(eq(9L), eq("BTCUSDT"), anyString(), eq(17L), eq(90))).thenReturn(1);
        when(mapper.lockOwned(eq(9L), eq("BTCUSDT"), anyString(), eq(17L))).thenReturn(17L);
        when(mapper.release(eq(9L), eq("BTCUSDT"), anyString(), eq(17L))).thenReturn(1);
        var service = new TradeHistoryCollectionLeaseService(mapper);

        var lease = service.acquire(9L, "BTCUSDT");
        assertNotNull(lease);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(lease, "renew");
        assertDoesNotThrow(lease::assertOwnedForCommit);
        lease.close();

        verify(mapper).ensure(eq(9L), eq("BTCUSDT"));
        verify(mapper).acquire(eq(9L), eq("BTCUSDT"), anyString(), eq(90));
        verify(mapper).renew(eq(9L), eq("BTCUSDT"), anyString(), eq(17L), eq(90));
        verify(mapper).lockOwned(eq(9L), eq("BTCUSDT"), anyString(), eq(17L));
        verify(mapper).release(eq(9L), eq("BTCUSDT"), anyString(), eq(17L));
        service.shutdown();
    }

    @Test
    void refusesCommitWhenTheLeaseEpochWasTakenOver() {
        var mapper = mock(TradeHistoryCollectionLeaseMapper.class);
        when(mapper.acquire(eq(9L), eq("ETHUSDT"), anyString(), eq(90))).thenReturn(1);
        when(mapper.currentEpoch(eq(9L), eq("ETHUSDT"), anyString())).thenReturn(21L);
        when(mapper.lockOwned(eq(9L), eq("ETHUSDT"), anyString(), eq(21L))).thenReturn(null);
        var service = new TradeHistoryCollectionLeaseService(mapper);

        var lease = service.acquire(9L, "ETHUSDT");
        assertNotNull(lease);
        assertThrows(IllegalStateException.class, lease::assertOwnedForCommit);
        lease.close();
        verify(mapper).release(eq(9L), eq("ETHUSDT"), anyString(), eq(21L));
        service.shutdown();
    }

    @Test
    void refusesCommitAfterLeaseRenewalFails() {
        var mapper = mock(TradeHistoryCollectionLeaseMapper.class);
        when(mapper.acquire(eq(9L), eq("BNBUSDT"), anyString(), eq(90))).thenReturn(1);
        when(mapper.currentEpoch(eq(9L), eq("BNBUSDT"), anyString())).thenReturn(25L);
        when(mapper.renew(eq(9L), eq("BNBUSDT"), anyString(), eq(25L), eq(90))).thenReturn(0);
        var service = new TradeHistoryCollectionLeaseService(mapper);

        var lease = service.acquire(9L, "BNBUSDT");
        assertNotNull(lease);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(lease, "renew");

        assertThrows(IllegalStateException.class, lease::assertOwnedForCommit);
        verify(mapper, never()).lockOwned(anyLong(), anyString(), anyString(), anyLong());
        lease.close();
        service.shutdown();
    }

    @Test
    void returnsNoLeaseWhenAnotherNodeOwnsTheSymbol() {
        var mapper = mock(TradeHistoryCollectionLeaseMapper.class);
        when(mapper.acquire(eq(9L), eq("SOLUSDT"), anyString(), eq(90))).thenReturn(0);
        var service = new TradeHistoryCollectionLeaseService(mapper);

        assertNull(service.acquire(9L, "SOLUSDT"));
        verify(mapper, never()).currentEpoch(anyLong(), anyString(), anyString());
        service.shutdown();
    }
}
