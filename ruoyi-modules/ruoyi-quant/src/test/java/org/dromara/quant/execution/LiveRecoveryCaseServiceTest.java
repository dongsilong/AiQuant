package org.dromara.quant.execution;

import org.dromara.quant.execution.mapper.LiveRecoveryApprovalMapper;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LiveRecoveryCaseServiceTest {
    private final LiveRecoveryApprovalMapper cases = mock(LiveRecoveryApprovalMapper.class);
    private final LiveRecoveryAuditMapper audit = mock(LiveRecoveryAuditMapper.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final TransactionStatus transactionStatus = mock(TransactionStatus.class);
    private final LiveRecoveryCaseService service;

    LiveRecoveryCaseServiceTest() {
        when(manager.getTransaction(any())).thenReturn(transactionStatus);
        service = new LiveRecoveryCaseService(cases, audit, manager);
    }

    @Test
    void approvalAndAuditMustSucceedTogether() {
        String hash = "a".repeat(64);
        String detail = "b".repeat(64);
        when(cases.approve(123L, 2L, hash)).thenReturn(1);
        when(audit.append(anyLong(), eq(123L), eq(2L), eq("APPROVE"), eq(hash), eq(detail)))
            .thenReturn(1);
        service.approve(123L, 2L, hash, detail);
        verify(manager).commit(transactionStatus);
    }

    @Test
    void auditFailureRollsBackSuccessfulApproval() {
        String hash = "a".repeat(64);
        String detail = "b".repeat(64);
        when(cases.approve(123L, 2L, hash)).thenReturn(1);
        assertThrows(IllegalStateException.class, () -> service.approve(123L, 2L, hash, detail));
        verify(manager).rollback(transactionStatus);
        verify(manager, never()).commit(any());
    }

    @Test
    void staleApprovalCannotWriteAudit() {
        String hash = "a".repeat(64);
        assertThrows(IllegalStateException.class, () ->
            service.approve(123L, 2L, hash, "b".repeat(64)));
        verifyNoInteractions(audit);
        verify(manager).rollback(transactionStatus);
    }

    @Test
    void invalidDigestCannotReachDatabase() {
        assertThrows(IllegalArgumentException.class, () ->
            service.approve(123L, 2L, "untrusted", "b".repeat(64)));
        verifyNoInteractions(cases, audit);
    }
}
