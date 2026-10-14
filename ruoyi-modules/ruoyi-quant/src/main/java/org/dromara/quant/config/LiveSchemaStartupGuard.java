package org.dromara.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * LIVE must never start against a half-migrated quant database. The checks are read-only.
 * Schema presence is necessary, not sufficient: Binance integration drills remain mandatory.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.live", name = "enabled", havingValue = "true")
public class LiveSchemaStartupGuard implements ApplicationRunner {
    private static final Set<String> TABLES = Set.of(
        "quant_credential", "quant_strategy", "quant_strategy_instance",
        "quant_strategy_position_state", "quant_strategy_runtime_lease",
        "quant_strategy_entry_guard", "quant_execution_record", "quant_open_capacity",
        "quant_order", "quant_position", "quant_protection_order", "quant_protection_target",
        "quant_trade", "quant_trade_decision_log");

    private static final Set<String> UNIQUE = Set.of(
        "quant_strategy_runtime_lease|credential_id,symbol",
        "quant_strategy_entry_guard|credential_id,symbol,kline_open_time",
        "quant_execution_record|credential_id,idempotency_key",
        "quant_open_capacity|credential_id,idempotency_key",
        "quant_strategy_position_state|instance_id",
        "quant_protection_target|target_key");

    private final LiveSchemaContractMapper mapper;

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    void verify() {
        List<String> actualTables = mapper.innodbTables();
        List<String> actualUnique = mapper.uniqueContracts();
        if (actualTables == null || actualUnique == null) {
            throw new IllegalStateException("LIVE database metadata unavailable; refuse to enable real trading");
        }
        var missingTables = new java.util.TreeSet<>(TABLES);
        missingTables.removeAll(actualTables);
        var missingUnique = new java.util.TreeSet<>(UNIQUE);
        missingUnique.removeAll(actualUnique);
        if (!missingTables.isEmpty() || !missingUnique.isEmpty()) {
            throw new IllegalStateException(
                "LIVE schema preflight rejected startup; missing InnoDB tables=" + missingTables
                + ", missing unique-index contracts=" + missingUnique);
        }
    }
}
