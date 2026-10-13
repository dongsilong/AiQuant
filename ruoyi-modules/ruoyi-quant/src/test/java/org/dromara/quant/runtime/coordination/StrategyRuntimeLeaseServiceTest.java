package org.dromara.quant.runtime.coordination;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Update;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLeaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyRuntimeLeaseServiceTest {
    @Test
    void leaseStatementsAreAtomicAndTimeoutBounded() throws Exception {
        var type = StrategyRuntimeLeaseMapper.class;
        var insert = type.getMethod("ensureRow", Long.class, Long.class, String.class, String.class, int.class);
        var acquire = type.getMethod("acquireOrRenew", Long.class, Long.class, String.class, String.class, int.class);
        var release = type.getMethod("release", Long.class, String.class, String.class);
        assertEquals(3, insert.getAnnotation(Options.class).timeout());
        assertEquals(3, acquire.getAnnotation(Options.class).timeout());
        assertEquals(3, release.getAnnotation(Options.class).timeout());
        assertTrue(insert.getAnnotation(Insert.class).value()[0].contains("INSERT IGNORE"));
        assertTrue(acquire.getAnnotation(Update.class).value()[0].contains("lease_until<NOW()"));
        assertTrue(acquire.getAnnotation(Update.class).value()[0].contains("owner_token=#{ownerToken}"));
        assertTrue(release.getAnnotation(Delete.class).value()[0].contains("owner_token=#{ownerToken}"));
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
    void databaseErrorFailsClosed() {
        StrategyRuntimeLeaseMapper mapper = mock(StrategyRuntimeLeaseMapper.class);
        doThrow(new IllegalStateException("database timeout")).when(mapper).ensureRow(anyLong(), anyLong(), anyString(), anyString(), anyInt());
        var service = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        assertFalse(service.acquireOrRenew(1L, 2L, "BTCUSDT"));
        verify(mapper, never()).acquireOrRenew(anyLong(), anyLong(), anyString(), anyString(), anyInt());
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
