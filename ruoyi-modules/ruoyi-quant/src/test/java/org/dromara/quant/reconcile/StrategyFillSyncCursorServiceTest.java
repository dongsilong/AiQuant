package org.dromara.quant.reconcile;

import org.dromara.quant.reconcile.mapper.StrategyFillSyncCursorMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyFillSyncCursorServiceTest {
    private final StrategyFillSyncCursorMapper mapper = mock(StrategyFillSyncCursorMapper.class);
    private final StrategyFillSyncCursorService service = new StrategyFillSyncCursorService(mapper);
    private final LocalDateTime cursor = LocalDateTime.of(2026, 10, 17, 0, 0);

    @Test
    void insertsFirstCursorWhenNoRowExists() {
        when(mapper.selectLastCompleteTo(1L, "BTCUSDT")).thenReturn(null);
        when(mapper.insertCursor(1L, "BTCUSDT", cursor)).thenReturn(1);

        assertDoesNotThrow(() -> service.advance(1L, "BTCUSDT", cursor));

        verify(mapper).insertCursor(1L, "BTCUSDT", cursor);
    }

    @Test
    void concurrentFirstInsertFallsBackToMonotonicUpdate() {
        when(mapper.selectLastCompleteTo(1L, "BTCUSDT")).thenReturn(null, cursor);
        when(mapper.insertCursor(1L, "BTCUSDT", cursor)).thenThrow(new DuplicateKeyException("concurrent insert"));
        when(mapper.advanceIfBehind(1L, "BTCUSDT", cursor)).thenReturn(0, 1);

        assertDoesNotThrow(() -> service.advance(1L, "BTCUSDT", cursor));

        verify(mapper, times(2)).advanceIfBehind(1L, "BTCUSDT", cursor);
    }

    @Test
    void neverRewindsAnAlreadyNewerCursor() {
        when(mapper.selectLastCompleteTo(1L, "BTCUSDT")).thenReturn(cursor.plusMinutes(1));

        assertDoesNotThrow(() -> service.advance(1L, "BTCUSDT", cursor));

        verify(mapper, never()).insertCursor(anyLong(), anyString(), any());
        verify(mapper, times(1)).advanceIfBehind(1L, "BTCUSDT", cursor);
    }

    @Test
    void futureRepairRequiresExactCapturedValueAndCompleteInterval() {
        var end = LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1);
        var future = end.plusHours(8);
        when(mapper.repairFuture(1L, "BTCUSDT", future, end)).thenReturn(1);
        assertDoesNotThrow(() -> service.repairFutureAfterCollection(1L, "BTCUSDT", future, end));
        when(mapper.repairFuture(1L, "BTCUSDT", future, end)).thenReturn(0);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> service.repairFutureAfterCollection(1L, "BTCUSDT", future, end));
        verify(mapper, never()).advanceIfBehind(any(), any(), any());
    }
}
