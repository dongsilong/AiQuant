package org.dromara.quant.execution;

import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutionRecordServiceTest {
    @Test
    void storesTheSignalReasonOnTheExecutionRecord() {
        QuantExecutionRecordMapper mapper = mock(QuantExecutionRecordMapper.class);
        when(mapper.insert(any(QuantExecutionRecord.class))).thenReturn(1);
        ExecutionRecordService service = new ExecutionRecordService(mapper);

        QuantExecutionRecord record = service.tryCreatePending(1L, 2L, 3L, "OPEN", "BTCUSDT", "idempotency",
            "client-order", "突破触发做多");

        assertEquals("突破触发做多", record.getTriggerReason());
    }
}
