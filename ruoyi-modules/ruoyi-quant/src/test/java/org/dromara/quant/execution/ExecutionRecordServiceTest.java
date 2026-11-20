package org.dromara.quant.execution;

import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ExecutionRecordServiceTest {
    private final QuantExecutionRecordMapper mapper = mock(QuantExecutionRecordMapper.class);
    private final ExecutionRecordService service = new ExecutionRecordService(mapper);

    @Test
    void recordsFirstVerifiedCloseAbsenceWithoutPrematurelyFailingUnknownExecution() {
        QuantExecutionRecord record = closeRecord();
        record.setExchangeOrderStatus("");
        LocalDateTime now = LocalDateTime.of(2026, 11, 20, 1, 30);

        when(mapper.recordVerifiedCloseAbsence(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);

        assertFalse(service.recordVerifiedCloseAbsence(record, "无成交", now));
        assertEquals("UNKNOWN", record.getExecutionStatus());
        assertTrue(service.hasVerifiedAbsencePending(record));
        assertEquals(now, record.getUpdatedAt());
        verify(mapper).recordVerifiedCloseAbsence(eq(1L), eq(2L), eq(3L), eq("JCTUSDT"),
            eq("key"), eq("client"), startsWith("VERIFIED_ABSENCE_PENDING:"), eq(now));
    }

    @Test
    void marksCloseFailedOnlyAfterSecondCompleteCheckOneMinuteLater() {
        QuantExecutionRecord record = closeRecord();
        LocalDateTime now = LocalDateTime.of(2026, 11, 20, 1, 32);
        record.setErrorMessage("VERIFIED_ABSENCE_PENDING:无成交");
        record.setUpdatedAt(now.minusMinutes(1));

        when(mapper.markCloseNotExecutedAfterVerifiedAbsence(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(1);

        assertTrue(service.recordVerifiedCloseAbsence(record, "无成交", now));
        assertEquals("FAILED", record.getExecutionStatus());
        assertTrue(service.hasVerifiedCloseAbsence(record));
        assertTrue(record.getErrorMessage().startsWith("VERIFIED_CLOSE_ABSENCE:"));
        verify(mapper).markCloseNotExecutedAfterVerifiedAbsence(eq(1L), eq(2L), eq(3L), eq("JCTUSDT"),
            eq("key"), eq("client"), startsWith("VERIFIED_CLOSE_ABSENCE:"), eq(now));
    }

    @Test
    void doesNotTreatAnUnrelatedFailedCloseAsVerifiedAbsence() {
        QuantExecutionRecord record = closeRecord();
        record.setExecutionStatus("FAILED");
        record.setErrorMessage("stage=QUERY_TRADING_RULES timeout");

        assertFalse(service.hasVerifiedCloseAbsence(record));
        assertFalse(service.recordVerifiedCloseAbsence(record, "无成交", LocalDateTime.now()));
        verifyNoInteractions(mapper);
    }

    private QuantExecutionRecord closeRecord() {
        QuantExecutionRecord record = new QuantExecutionRecord();
        record.setId(1L);
        record.setCredentialId(2L);
        record.setStrategyInstanceId(3L);
        record.setOperationType("CLOSE");
        record.setSymbol("JCTUSDT");
        record.setIdempotencyKey("key");
        record.setClientOrderId("client");
        record.setExecutionStatus("UNKNOWN");
        return record;
    }
}
