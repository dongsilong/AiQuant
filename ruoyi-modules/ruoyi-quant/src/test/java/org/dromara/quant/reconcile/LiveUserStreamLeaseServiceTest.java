package org.dromara.quant.reconcile;

import org.dromara.quant.reconcile.mapper.LiveUserStreamLeaseMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveUserStreamLeaseServiceTest {
    @Test
    void reacquisitionWithSameProcessTokenReturnsNewEpochAndFencesPreviousOne() {
        var mapper = mock(LiveUserStreamLeaseMapper.class);
        when(mapper.acquireOrRenew(eq(7L), anyString(), eq(75))).thenReturn(1);
        when(mapper.currentEpoch(eq(7L), anyString())).thenReturn(12L, 13L);
        when(mapper.ownsEpoch(eq(7L), anyString(), anyLong())).thenReturn(1L);
        var service = new LiveUserStreamLeaseService(mapper);

        assertEquals(12L, service.acquire(7L, 75));
        assertTrue(service.owns(7L, 12L));
        assertEquals(13L, service.acquire(7L, 75));

        assertFalse(service.owns(7L, 12L));
        assertTrue(service.owns(7L, 13L));
        verify(mapper, times(2)).ensure(eq(7L), anyString(), eq(75));
    }
}
