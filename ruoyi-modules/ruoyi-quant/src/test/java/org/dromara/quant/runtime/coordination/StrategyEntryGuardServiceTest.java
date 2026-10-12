package org.dromara.quant.runtime.coordination;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class StrategyEntryGuardServiceTest {
    private JdbcTemplate database() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE quant_strategy_entry_guard (id BIGINT AUTO_INCREMENT PRIMARY KEY, credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL, instance_id BIGINT NOT NULL, kline_open_time TIMESTAMP NOT NULL, order_id BIGINT, created_at TIMESTAMP NOT NULL, UNIQUE(credential_id,symbol,kline_open_time))");
        return jdbc;
    }

    @Test
    void concurrentInstancesAndRestartCannotReopenSuccessfulCandle() throws Exception {
        var jdbc = database();
        var first = new StrategyEntryGuardService(jdbc);
        var second = new StrategyEntryGuardService(jdbc);
        var candle = Instant.parse("2026-10-12T00:00:00Z");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = executor.submit(() -> first.tryClaim(1L, 10L, "btcusdt", candle));
            var two = executor.submit(() -> second.tryClaim(2L, 10L, "BTCUSDT", candle));
            boolean won = one.get();
            assertNotEquals(won, two.get());
            long owner = won ? 1L : 2L;
            first.bindOrder(owner, 10L, "BTCUSDT", candle, 55L);
            first.release(owner, 10L, "BTCUSDT", candle);
            var restarted = new StrategyEntryGuardService(jdbc);
            assertFalse(restarted.tryClaim(3L, 10L, "BTCUSDT", candle));
            assertTrue(restarted.tryClaim(3L, 10L, "BTCUSDT", candle.plusSeconds(60)));
            assertTrue(restarted.tryClaim(3L, 11L, "BTCUSDT", candle));
            assertTrue(restarted.tryClaim(3L, 10L, "ETHUSDT", candle));
        }
    }

    @Test
    void unknownClaimIsRetainedAndOnlyItsOwnerCanBindOrReleaseIt() {
        var jdbc = database();
        var guard = new StrategyEntryGuardService(jdbc);
        var candle = Instant.parse("2026-10-12T00:00:00Z");
        assertTrue(guard.tryClaim(1L, 10L, "BTCUSDT", candle));
        assertFalse(new StrategyEntryGuardService(jdbc).tryClaim(2L, 10L, "BTCUSDT", candle));
        guard.bindOrder(2L, 10L, "BTCUSDT", candle, 99L);
        assertNull(jdbc.queryForObject("SELECT order_id FROM quant_strategy_entry_guard", Long.class));
        guard.release(2L, 10L, "BTCUSDT", candle);
        assertFalse(guard.tryClaim(2L, 10L, "BTCUSDT", candle));
        guard.release(1L, 10L, "BTCUSDT", candle);
        assertTrue(guard.tryClaim(2L, 10L, "BTCUSDT", candle));
    }
}
