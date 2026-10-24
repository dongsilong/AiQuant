package org.dromara.quant.runtime.coordination;

import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLeaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Tag("dev")
class StrategyRuntimeLeaseServiceTest {
    @Test
    void leaseStatementsAreAtomicAndTimeoutBounded() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/mapper/quant/StrategyRuntimeLeaseMapper.xml")) {
            assertNotNull(stream, "lease mapper XML must be available to tests");
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(xml.contains("<insert id=\"ensureRow\" timeout=\"3\">"));
            assertTrue(xml.contains("<update id=\"acquireOrRenew\" timeout=\"3\">"));
            assertTrue(xml.contains("<update id=\"release\" timeout=\"3\">"));
            assertTrue(xml.contains("INSERT IGNORE INTO quant_strategy_runtime_lease"));
            assertTrue(xml.contains("lease_until<=NOW()"));
            assertTrue(xml.contains("owner_token=#{ownerToken}"));
            assertTrue(xml.contains("lease_until=DATE_SUB"));
            assertTrue(xml.contains("lease_epoch+1"));
        }
    }

    @Test
    void acquireOnlySucceedsWhenConditionalUpdateSucceeds() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.acquireOrRenew(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt())).thenReturn(1, 0);
        assertTrue(service.acquireOrRenew(1L, 2L, "btcusdt"));
        assertFalse(service.acquireOrRenew(1L, 2L, "BTCUSDT"));
        verify(mapper, times(2)).ensureRow(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt());
    }

    @Test
    void scheduledRenewalUsesOneConditionalUpdateAndDoesNotInitializeRow() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.acquireOrRenew(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt())).thenReturn(1, 0);
        assertTrue(service.renew(1L, 2L, "btcusdt"));
        assertFalse(service.renew(1L, 2L, "BTCUSDT"));
        verify(mapper, times(2)).acquireOrRenew(eq(1L), eq(2L), eq("BTCUSDT"), anyString(), anyInt());
        verify(mapper, never()).ensureRow(anyLong(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void readOnlyOwnershipCheckFailsClosed() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.countCurrentOwner(eq(1L), eq(2L), eq("BTCUSDT"), anyString())).thenReturn(1L, 0L);
        assertTrue(service.isCurrentOwner(1L, 2L, "btcusdt"));
        assertFalse(service.isCurrentOwner(1L, 2L, "BTCUSDT"));
        doThrow(new IllegalStateException("database timeout")).when(mapper)
            .countCurrentOwner(eq(1L), eq(2L), eq("BTCUSDT"), anyString());
        assertFalse(service.isCurrentOwner(1L, 2L, "BTCUSDT"));
    }

    @Test
    void databaseErrorFailsClosed() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        doThrow(new IllegalStateException("database timeout")).when(mapper).ensureRow(anyLong(), anyLong(), anyString(), anyString(), anyInt());
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        assertFalse(service.acquireOrRenew(1L, 2L, "BTCUSDT"));
        verify(mapper, never()).acquireOrRenew(anyLong(), anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void finalOwnerCheckUsesSameTokenAndRejectsExpiredOrStolenLease() {
        var mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        when(mapper.currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), anyString())).thenReturn(1L, 0L);
        service.requireCurrentOwner(1L, 2L, "btcusdt");
        assertThrows(IllegalStateException.class, () -> service.requireCurrentOwner(1L, 2L, "BTCUSDT"));
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).currentEpoch(eq(1L), eq(2L), eq("BTCUSDT"), captor.capture());
        assertEquals(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }

    @Test
    void finalOwnerCheckFailsClosedOnDatabaseTimeout() {
        var mapper = mock(StrategyRuntimeLeaseMapper.class);
        doThrow(new IllegalStateException("db unavailable")).when(mapper).currentEpoch(anyLong(), anyLong(), anyString(), anyString());
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        assertThrows(IllegalStateException.class, () -> service.requireCurrentOwner(1L, 2L, "BTCUSDT"));
    }

    @Test
    void releaseOnlyUsesSameOwnerTokenAndNormalizedSymbol() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        service.acquireOrRenew(1L, 2L, "btcusdt");
        service.release(1L, 2L, "btcusdt");
        var token = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper).ensureRow(eq(1L), eq(2L), eq("BTCUSDT"), token.capture(), anyInt());
        verify(mapper).release(2L, "BTCUSDT", token.getValue());
    }
}
