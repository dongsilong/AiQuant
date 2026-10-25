package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class LiveSchemaStartupGuardTest {
    @Test
    void refusesStartupWhenARequiredTradingTableOrUniqueIndexIsMissing() {
        var mapper = mock(LiveSchemaContractMapper.class);
        var guard = new LiveSchemaStartupGuard(mapper);
        when(mapper.innodbTables()).thenReturn(List.of("quant_credential"));
        when(mapper.uniqueContracts()).thenReturn(List.of());
        when(mapper.leaseEpochColumns()).thenReturn(List.of());
        when(mapper.entryGuardColumns()).thenReturn(List.of("quant_strategy_entry_guard|guard_type"));
        when(mapper.positionReturnRateColumns()).thenReturn(List.of());
        var error = assertThrows(IllegalStateException.class, guard::verify);
        assertTrue(error.getMessage().contains("quant_strategy_runtime_lease"));
        assertTrue(error.getMessage().contains("quant_execution_record|credential_id,idempotency_key"));
        assertTrue(error.getMessage().contains("quant_strategy_fill_journal"));
        assertTrue(error.getMessage().contains("quant_strategy_fill_sync_cursor"));
        assertTrue(error.getMessage().contains("quant_strategy_order_intent"));
        assertTrue(error.getMessage().contains("quant_strategy_pre_dispatch_intent"));
        assertTrue(error.getMessage().contains("quant_live_recovery_case"));
        assertTrue(error.getMessage().contains("V20261017_01__strategy_fill_journal.sql"));
        assertTrue(error.getMessage().contains("V20261014_09__strategy_order_intent.sql"));
        assertTrue(error.getMessage().contains("V20261014_10__pre_dispatch_strategy_intent.sql"));
        assertTrue(error.getMessage().contains("V20261014_05__recovery_case_audit.sql"));
        assertTrue(error.getMessage().contains("V20261021_01__strategy_position_maximum_return_rate.sql"));
        assertTrue(!error.getMessage().contains("V20261016_01__close_candle_entry_guard.sql"));
    }

    @Test
    void refusesStartupWhenFencingEpochColumnHasNotBeenMigrated() {
        var mapper = mock(LiveSchemaContractMapper.class);
        // Other metadata is non-null but may be incomplete; epoch is a mandatory release gate.
        when(mapper.innodbTables()).thenReturn(List.of("quant_strategy_runtime_lease"));
        when(mapper.uniqueContracts()).thenReturn(List.of("quant_strategy_runtime_lease|credential_id,symbol"));
        when(mapper.leaseEpochColumns()).thenReturn(List.of());
        when(mapper.entryGuardColumns()).thenReturn(List.of());
        when(mapper.positionReturnRateColumns()).thenReturn(List.of("maximum_return_rate"));
        var ex = assertThrows(IllegalStateException.class, new LiveSchemaStartupGuard(mapper)::verify);
        assertTrue(ex.getMessage().contains("leaseEpochMissing=true"));
    }

    @Test
    void refusesStartupWhenCloseCandleGuardMigrationHasNotBeenApplied() {
        var mapper = mock(LiveSchemaContractMapper.class);
        when(mapper.innodbTables()).thenReturn(List.of());
        when(mapper.uniqueContracts()).thenReturn(List.of());
        when(mapper.leaseEpochColumns()).thenReturn(List.of("quant_strategy_runtime_lease|lease_epoch"));
        when(mapper.entryGuardColumns()).thenReturn(List.of());
        when(mapper.positionReturnRateColumns()).thenReturn(List.of("maximum_return_rate"));
        var ex = assertThrows(IllegalStateException.class, new LiveSchemaStartupGuard(mapper)::verify);
        assertTrue(ex.getMessage().contains("entryGuardTypeMissing=true"));
        assertTrue(ex.getMessage().contains("V20261016_01__close_candle_entry_guard.sql"));
    }

    @Test
    void refusesStartupWhenMetadataCannotBeRead() {
        var mapper = mock(LiveSchemaContractMapper.class);
        assertThrows(IllegalStateException.class, new LiveSchemaStartupGuard(mapper)::verify);
    }
}
