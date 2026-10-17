package org.dromara.quant.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * LIVE must never start against a half-migrated quant database. The checks are read-only.
 * Schema presence is necessary, not sufficient: Binance integration drills remain mandatory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.live", name = "enabled", havingValue = "true")
public class LiveSchemaStartupGuard implements ApplicationRunner {
    private static final Set<String> TABLES = Set.of("quant_credential", "quant_strategy", "quant_strategy_instance", "quant_strategy_position_state",
        "quant_strategy_runtime_lease", "quant_strategy_entry_guard", "quant_execution_record", "quant_open_capacity", "quant_order", "quant_position", "quant_protection_order",
        "quant_protection_target", "quant_trade", "quant_trade_decision_log", "quant_live_dispatch_guard", "quant_order_dispatch_ticket", "quant_strategy_fill_allocation",
        "quant_live_account_settings_intent", "quant_position_sync_fence", "quant_live_user_stream_lease");

    private static final Set<String> UNIQUE = Set.of("quant_strategy_runtime_lease|credential_id,symbol", "quant_strategy_entry_guard|credential_id,symbol,kline_open_time",
        "quant_execution_record|credential_id,idempotency_key", "quant_open_capacity|credential_id,idempotency_key", "quant_strategy_position_state|instance_id",
        "quant_protection_target|target_key", "quant_live_dispatch_guard|credential_id,symbol", "quant_order_dispatch_ticket|credential_id,idempotency_key",
        "quant_strategy_fill_allocation|credential_id,symbol,exchange_trade_id", "quant_live_account_settings_intent|credential_id,symbol");

    private final LiveSchemaContractMapper mapper;

    /**
     * 应用启动时验证 LIVE 运行所需的数据库结构。
     */
    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    void verify() {
        List<String> actualTables = mapper.innodbTables();
        List<String> actualUnique = mapper.uniqueContracts();
        List<String> leaseEpoch = mapper.leaseEpochColumns();
        List<String> entryGuardColumns = mapper.entryGuardColumns();
        if (actualTables == null || actualUnique == null || leaseEpoch == null || entryGuardColumns == null) {
            throw new IllegalStateException("LIVE database metadata unavailable; refuse to enable real trading");
        }
        var missingTables = new java.util.TreeSet<>(TABLES);
        missingTables.removeAll(actualTables);
        var missingUnique = new java.util.TreeSet<>(UNIQUE);
        missingUnique.removeAll(actualUnique);
        boolean missingEpoch = !leaseEpoch.contains("quant_strategy_runtime_lease|lease_epoch");
        boolean missingEntryGuardType = !entryGuardColumns.contains("quant_strategy_entry_guard|guard_type");
        if (!missingTables.isEmpty() || !missingUnique.isEmpty() || missingEpoch || missingEntryGuardType) {
            throw new IllegalStateException("LIVE schema preflight rejected startup; missing InnoDB tables=" + missingTables + ", missing unique-index contracts=" + missingUnique + ", leaseEpochMissing=" + missingEpoch + ", entryGuardTypeMissing=" + missingEntryGuardType + ". Apply the missing schema migrations to the application's configured MySQL database " + "(for candle close guards: script/sql/quant/V20261016_01__close_candle_entry_guard.sql), " + "then restart; LIVE schema preflight does not auto-create trading tables.");
        }
    }
}
