package org.dromara.quant.runtime.coordination;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLeaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyRuntimeLeaseServiceTest {
    private static final Long CREDENTIAL_RUNTIME_VERSION = 11L;

    @Test
    void leaseStatementsAreAtomicAndTimeoutBounded() throws Exception {
        try (var input = getClass().getResourceAsStream("/mapper/quant/StrategyRuntimeLeaseMapper.xml")) {
            assertNotNull(input);
            String mapperXml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(mapperXml.contains("INSERT IGNORE"));
            assertTrue(mapperXml.contains("lease_until=DATE_ADD(NOW(), INTERVAL #{leaseSeconds} SECOND)"));
            assertTrue(mapperXml.contains("lease_epoch=CASE WHEN owner_token<>#{ownerToken} OR lease_until<=NOW()"));
            assertTrue(mapperXml.contains("lease_epoch+1"));
            assertTrue(mapperXml.contains("lease_until=DATE_SUB"));
            assertTrue(mapperXml.contains("c.credential_runtime_version = #{credentialRuntimeVersion}"));
            assertTrue(mapperXml.contains("timeout=\"3\""));
        }
        var type = StrategyRuntimeLeaseMapper.class;
        assertEquals(Long.class, type.getMethod("acquireOrRenew", Long.class, Long.class, String.class, String.class, int.class, Long.class)
            .getParameterTypes()[5]);
        assertEquals(Long.class, type.getMethod("currentEpoch", Long.class, Long.class, String.class, String.class, Long.class)
            .getParameterTypes()[4]);
    }

    @Test
    void acquireOnlySucceedsWhenConditionalUpdateSucceeds() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.acquireOrRenew(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt(), eq(CREDENTIAL_RUNTIME_VERSION))).thenReturn(1, 0);
        assertTrue(service.acquireOrRenew(1L, 2L, "btcusdt", CREDENTIAL_RUNTIME_VERSION));
        assertFalse(service.acquireOrRenew(1L, 2L, "BTCUSDT", CREDENTIAL_RUNTIME_VERSION));
        verify(mapper, times(2)).ensureRow(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt());
    }

    @Test
    void databaseErrorFailsClosed() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        doThrow(new IllegalStateException("database timeout")).when(mapper).ensureRow(anyLong(), anyLong(), anyString(), anyString(), anyInt());
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        assertFalse(service.acquireOrRenew(1L, 2L, "BTCUSDT", CREDENTIAL_RUNTIME_VERSION));
        verify(mapper, never()).acquireOrRenew(anyLong(), anyLong(), anyString(), anyString(), anyInt(), anyLong());
    }

    @Test
    void acquireOrRenewSnapshotReturnsVerifiedEpochWithConservativeExpiry() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.acquireOrRenew(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt(), eq(CREDENTIAL_RUNTIME_VERSION))).thenReturn(1);
        when(mapper.currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), eq(CREDENTIAL_RUNTIME_VERSION))).thenReturn(9L);

        long before = System.nanoTime();
        var snapshot = service.acquireOrRenewSnapshot(1L, 2L, "btcusdt", CREDENTIAL_RUNTIME_VERSION);
        assertNotNull(snapshot);
        long remainingNanos = snapshot.validUntilNanos() - System.nanoTime();

        assertEquals(9L, snapshot.epoch());
        assertTrue(snapshot.validUntilNanos() > before);
        assertTrue(remainingNanos > 0L);
        assertTrue(remainingNanos <= java.util.concurrent.TimeUnit.SECONDS.toNanos(28));
        verify(mapper).currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), eq(CREDENTIAL_RUNTIME_VERSION));
    }

    @Test
    void acquireOrRenewSnapshotFailsClosedWhenEpochCannotBeVerified() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.acquireOrRenew(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt(), eq(CREDENTIAL_RUNTIME_VERSION))).thenReturn(1);
        when(mapper.currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), eq(CREDENTIAL_RUNTIME_VERSION))).thenReturn(null);

        assertNull(service.acquireOrRenewSnapshot(1L, 2L, "BTCUSDT", CREDENTIAL_RUNTIME_VERSION));
    }

    @Test
    void finalOwnerCheckUsesSameTokenAndRejectsExpiredOrStolenLease() {
        var mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), eq(CREDENTIAL_RUNTIME_VERSION))).thenReturn(1L, 0L);
        service.requireCurrentOwner(1L, 2L, "btcusdt", CREDENTIAL_RUNTIME_VERSION);
        assertThrows(IllegalStateException.class, () -> service.requireCurrentOwner(1L, 2L, "BTCUSDT", CREDENTIAL_RUNTIME_VERSION));
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), captor.capture(), eq(CREDENTIAL_RUNTIME_VERSION));
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }

    @Test
    void finalOwnerCheckFailsClosedOnDatabaseTimeout() {
        var mapper = mock(StrategyRuntimeLeaseMapper.class);
        doThrow(new IllegalStateException("db unavailable")).when(mapper).currentEpoch(anyLong(), anyLong(), anyString(), anyString(), anyLong());
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        assertThrows(IllegalStateException.class, () -> service.requireCurrentOwner(1L, 2L, "BTCUSDT", CREDENTIAL_RUNTIME_VERSION));
    }

    @Test
    void releaseOnlyUsesSameOwnerTokenAndNormalizedSymbol() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        service.acquireOrRenew(1L, 2L, "btcusdt", CREDENTIAL_RUNTIME_VERSION);
        service.release(1L, 2L, "btcusdt");
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).ensureRow(eq(1L), eq(2L), eq("BTCUSDT"), token.capture(), anyInt());
        verify(mapper).release(2L, "BTCUSDT", token.getValue());
    }
}
