package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class LiveSchemaStartupGuardTest {
    @Test
    void refusesStartupWhenARequiredTradingTableOrUniqueIndexIsMissing() {
        var mapper = mock(LiveSchemaContractMapper.class);
        var guard = new LiveSchemaStartupGuard(mapper);
        when(mapper.innodbTables()).thenReturn(List.of("quant_credential"));
        when(mapper.uniqueContracts()).thenReturn(List.of());
        var error = assertThrows(IllegalStateException.class, guard::verify);
        assertTrue(error.getMessage().contains("quant_strategy_runtime_lease"));
        assertTrue(error.getMessage().contains("quant_execution_record|credential_id,idempotency_key"));
    }

    @Test
    void refusesStartupWhenMetadataCannotBeRead() {
        var mapper = mock(LiveSchemaContractMapper.class);
        assertThrows(IllegalStateException.class, new LiveSchemaStartupGuard(mapper)::verify);
    }
}
