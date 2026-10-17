package org.dromara.quant.execution;

import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class LivePreDispatchIntentTest {
    private final LiveOrderDispatchMapper mapper = mock(LiveOrderDispatchMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus status = mock(TransactionStatus.class);
    private final LiveOrderDispatcher dispatcher;

    LivePreDispatchIntentTest() {
        when(manager.getTransaction(any())).thenReturn(status);
        when(mapper.claimWithLease(1L, "BTCUSDT", "dispatch-a", 3L, 4L)).thenReturn(1);
        when(mapper.insertTicket(1L, "BTCUSDT", "dispatch-a", "OPEN", "cid-a", 3L, 4L)).thenReturn(1);
        dispatcher = new LiveOrderDispatcher(mapper, manager);
    }

    @Test
    void ticketAndImmutablePresendIntentCommitTogether() {
        when(mapper.insertPreDispatchIntent(1L, "BTCUSDT", "dispatch-a", "cid-a", 2L, 3L, "OPEN", "LONG", "BUY", 4L)).thenReturn(1);
        var ticket = dispatcher.beginStrategy(1L, "BTCUSDT", "dispatch-a", "OPEN", "cid-a", 2L, 3L, 4L, "LONG", "BUY");
        assertEquals("dispatch-a", ticket.key());
        verify(mapper).insertPreDispatchIntent(1L, "BTCUSDT", "dispatch-a", "cid-a", 2L, 3L, "OPEN", "LONG", "BUY", 4L);
        verify(manager).commit(status);
    }

    @Test
    void failedIntentInsertRollsBackOutboundTicketClaim() {
        assertThrows(IllegalStateException.class, () -> dispatcher.beginStrategy(1L, "BTCUSDT", "dispatch-a", "OPEN", "cid-a", 2L, 3L, 4L, "LONG", "BUY"));
        verify(mapper).insertTicket(1L, "BTCUSDT", "dispatch-a", "OPEN", "cid-a", 3L, 4L);
        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
    }

    @Test
    void contradictoryOpeningSideNeverAcquiresGuard() {
        assertThrows(IllegalArgumentException.class, () -> dispatcher.beginStrategy(1L, "BTCUSDT", "dispatch-a", "OPEN", "cid-a", 2L, 3L, 4L, "LONG", "SELL"));
        verifyNoInteractions(mapper);
    }
}
