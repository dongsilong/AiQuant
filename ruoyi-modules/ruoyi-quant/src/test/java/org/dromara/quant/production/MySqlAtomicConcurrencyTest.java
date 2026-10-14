package org.dromara.quant.production;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.execution.OpenPositionCommand;
import org.dromara.quant.market.AccountBalanceSnapshot;
import org.dromara.quant.risk.OpenCapacityService;
import org.dromara.quant.risk.RiskDecisionService;
import org.dromara.quant.risk.mapper.OpenCapacityMapper;
import org.dromara.quant.runtime.coordination.StrategyEntryGuardService;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.coordination.mapper.StrategyEntryGuardMapper;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLeaseMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Actual MySQL/InnoDB concurrency contract. CI provides a disposable MySQL service.
 * Must never run against live production DB; test drops and recreates quant_* tables!
 */
@Tag("mysql")
@EnabledIfEnvironmentVariable(named = "AIQUANT_MYSQL_URL", matches = ".+")
class MySqlAtomicConcurrencyTest {
    private static DriverManagerDataSource source() {
        String url = System.getenv("AIQUANT_MYSQL_URL");
        // This test intentionally drops tables. Never permit any database name except the dedicated CI DB.
        if (url == null || !url.matches("(?i)^jdbc:mysql://[^/]+/aiquant_ci_test(?:\\?.*)?$")) {
            throw new IllegalStateException("MySQL concurrency tests must use the disposable aiquant_ci_test database only");
        }
        return new DriverManagerDataSource(url,
            System.getenv().getOrDefault("AIQUANT_MYSQL_USERNAME", "root"),
            System.getenv().getOrDefault("AIQUANT_MYSQL_PASSWORD", "only_for_ci"));
    }

    private static SqlSessionTemplate session(DriverManagerDataSource source, Class<?>... mappers) throws Exception {
        var cfg = new Configuration();
        for (var mapper : mappers) { cfg.addMapper(mapper); }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(cfg);
        return new SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()));
    }

    @Test
    void separateNodesCannotOwnSameLeaseOrDuplicateSameCandle() throws Exception {
        var source = source();
        var db = new JdbcTemplate(source);
        db.execute("DROP TABLE IF EXISTS quant_strategy_runtime_lease");
        db.execute("DROP TABLE IF EXISTS quant_strategy_entry_guard");
        db.execute("""
            CREATE TABLE quant_strategy_runtime_lease (
                instance_id BIGINT NOT NULL, credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
                owner_token VARCHAR(128) NOT NULL, lease_until DATETIME NOT NULL,
                create_time DATETIME, update_time DATETIME,
                UNIQUE KEY uk_lease (credential_id, symbol)
            ) ENGINE=InnoDB
            """);
        // Exercise the actual additive upgrade shape against the legacy table,
        // rather than starting tests with the column already present.
        db.execute("""
            ALTER TABLE quant_strategy_runtime_lease
              ADD COLUMN lease_epoch BIGINT NOT NULL DEFAULT 1
              COMMENT 'Monotonic owner epoch; increment on lease takeover or expiry'
            """);
        db.execute("""
            CREATE TABLE quant_strategy_entry_guard (
                id BIGINT AUTO_INCREMENT PRIMARY KEY, credential_id BIGINT NOT NULL,
                symbol VARCHAR(32) NOT NULL, instance_id BIGINT NOT NULL,
                kline_open_time DATETIME NOT NULL, order_id BIGINT, created_at DATETIME NOT NULL,
                UNIQUE KEY uk_entry_guard (credential_id, symbol, kline_open_time)
            ) ENGINE=InnoDB
            """);
        var session = session(source, StrategyRuntimeLeaseMapper.class, StrategyEntryGuardMapper.class);
        var mapper = session.getMapper(StrategyRuntimeLeaseMapper.class);
        var first = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        var second = new StrategyRuntimeLeaseService(mapper, new QuantProperties());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = pool.submit(() -> { start.await(); return first.acquireOrRenew(10L, 1L, "BTCUSDT"); });
            var b = pool.submit(() -> { start.await(); return second.acquireOrRenew(20L, 1L, "BTCUSDT"); });
            start.countDown();
            boolean firstWon = a.get(15, TimeUnit.SECONDS);
            boolean secondWon = b.get(15, TimeUnit.SECONDS);
            assertNotEquals(firstWon, secondWon, "Exactly one owner is required");
            if (firstWon) {
                assertEquals(1L, first.requireCurrentEpoch(10L, 1L, "BTCUSDT"));
                assertThrows(IllegalStateException.class,
                    () -> second.requireCurrentOwner(20L, 1L, "BTCUSDT"));
                second.release(20L, 1L, "BTCUSDT");
                assertFalse(second.acquireOrRenew(20L, 1L, "BTCUSDT"));
                first.release(10L, 1L, "BTCUSDT");
                assertTrue(second.acquireOrRenew(20L, 1L, "BTCUSDT"));
                assertEquals(2L, second.requireCurrentEpoch(20L, 1L, "BTCUSDT"),
                    "Takeover must increment the persistent fencing epoch");
                assertThrows(IllegalStateException.class,
                    () -> first.requireCurrentOwner(10L, 1L, "BTCUSDT"));
                assertEquals(2L, second.requireCurrentEpoch(20L, 1L, "BTCUSDT"));
                assertTrue(second.acquireOrRenew(20L, 1L, "BTCUSDT"));
                assertEquals(2L, second.requireCurrentEpoch(20L, 1L, "BTCUSDT"),
                    "Renewal by the same active owner must not increment epoch");
                second.release(20L, 1L, "BTCUSDT");
                assertTrue(second.acquireOrRenew(20L, 1L, "BTCUSDT"));
                assertEquals(3L, second.requireCurrentEpoch(20L, 1L, "BTCUSDT"),
                    "Reacquiring an expired/released lease increments epoch even on the same node");
            } else {
                assertTrue(second.requireCurrentEpoch(20L, 1L, "BTCUSDT") >= 1);
                assertThrows(IllegalStateException.class,
                    () -> first.requireCurrentOwner(10L, 1L, "BTCUSDT"));
                first.release(10L, 1L, "BTCUSDT");
                assertFalse(first.acquireOrRenew(10L, 1L, "BTCUSDT"));
                second.release(20L, 1L, "BTCUSDT");
                assertTrue(first.acquireOrRenew(10L, 1L, "BTCUSDT"));
                assertEquals(2L, first.requireCurrentEpoch(10L, 1L, "BTCUSDT"),
                    "Takeover must increment the persistent fencing epoch");
                assertThrows(IllegalStateException.class,
                    () -> second.requireCurrentOwner(20L, 1L, "BTCUSDT"));
                first.requireCurrentOwner(10L, 1L, "BTCUSDT");
                assertTrue(first.acquireOrRenew(10L, 1L, "BTCUSDT"));
                assertEquals(2L, first.requireCurrentEpoch(10L, 1L, "BTCUSDT"),
                    "Same-owner renewal must keep the epoch");
                first.release(10L, 1L, "BTCUSDT");
                assertTrue(first.acquireOrRenew(10L, 1L, "BTCUSDT"));
                assertEquals(3L, first.requireCurrentEpoch(10L, 1L, "BTCUSDT"),
                    "Same-owner reacquisition must increment the epoch");
            }
        }
        var entry = session.getMapper(StrategyEntryGuardMapper.class);
        var firstGuard = new StrategyEntryGuardService(entry);
        var secondGuard = new StrategyEntryGuardService(entry);
        var candle = Instant.parse("2026-10-14T00:00:00Z");
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var x = pool.submit(() -> { gate.await(); return firstGuard.tryClaim(1L, 1L, "BTCUSDT", candle); });
            var y = pool.submit(() -> { gate.await(); return secondGuard.tryClaim(2L, 1L, "BTCUSDT", candle); });
            gate.countDown();
            assertNotEquals(x.get(15, TimeUnit.SECONDS), y.get(15, TimeUnit.SECONDS));
            assertEquals(1L, db.queryForObject("SELECT COUNT(*) FROM quant_strategy_entry_guard", Long.class));
        }
    }

    @Test
    void realInnoDbAccountLockSerializesConcurrentCapacityReservations() throws Exception {
        var source = source();
        var jdbc = new JdbcTemplate(source);
        for (var name : List.of("quant_open_capacity", "quant_execution_record", "quant_strategy_position_state",
                "quant_strategy_instance", "quant_strategy_symbol", "quant_symbol", "quant_position",
                "quant_risk_config", "quant_strategy", "quant_credential")) {
            jdbc.execute("DROP TABLE IF EXISTS " + name);
        }
        jdbc.execute("CREATE TABLE quant_credential (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_risk_config (credential_id BIGINT, max_open_positions INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_strategy (id BIGINT PRIMARY KEY, max_open_count INT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_position (credential_id BIGINT, symbol VARCHAR(32), position_side VARCHAR(8), quantity DECIMAL(30,16)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_strategy_instance (id BIGINT, strategy_id BIGINT, credential_id BIGINT, strategy_symbol_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_strategy_symbol (id BIGINT, symbol_id BIGINT) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_symbol (id BIGINT, symbol VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_strategy_position_state (instance_id BIGINT, status VARCHAR(32), position_qty DECIMAL(30,16), direction VARCHAR(8), updated_at DATETIME, pending_action VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("CREATE TABLE quant_execution_record (credential_id BIGINT, idempotency_key VARCHAR(160), execution_status VARCHAR(16), exchange_order_status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("""
            CREATE TABLE quant_open_capacity (credential_id BIGINT, idempotency_key VARCHAR(160), strategy_id BIGINT,
            instance_id BIGINT, symbol VARCHAR(32), position_side VARCHAR(8), reserved_margin DECIMAL(38,16),
            status VARCHAR(16), created_at DATETIME, updated_at DATETIME,
            PRIMARY KEY (credential_id,idempotency_key)) ENGINE=InnoDB
            """);
        jdbc.update("INSERT INTO quant_credential VALUES (1)");
        jdbc.update("INSERT INTO quant_risk_config VALUES (1,1)");
        jdbc.update("INSERT INTO quant_strategy VALUES (10,1)");
        var mapper = session(source, OpenCapacityMapper.class).getMapper(OpenCapacityMapper.class);
        var manager = new DataSourceTransactionManager(source);
        var a = new OpenCapacityService(mapper, manager, mock(RiskDecisionService.class));
        var b = new OpenCapacityService(mapper, manager, mock(RiskDecisionService.class));
        var gate = new CountDownLatch(1);
        var balance = new AccountBalanceSnapshot(1L, new BigDecimal("100"), Set.of(),
            System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var x = pool.submit(() -> {
                gate.await();
                try {
                    a.reserve(order("BTCUSDT", "first"), BigDecimal.TEN, List.of(), balance);
                    return true;
                } catch (OpenCapacityService.CapacityBlockedException blocked) { return false; }
            });
            var y = pool.submit(() -> {
                gate.await();
                try {
                    b.reserve(order("ETHUSDT", "second"), BigDecimal.TEN, List.of(), balance);
                    return true;
                } catch (OpenCapacityService.CapacityBlockedException blocked) { return false; }
            });
            gate.countDown();
            assertNotEquals(x.get(15, TimeUnit.SECONDS), y.get(15, TimeUnit.SECONDS),
                "InnoDB account FOR UPDATE must serialize capacity admission");
        }
        assertEquals(1L, jdbc.queryForObject(
            "SELECT COUNT(*) FROM quant_open_capacity WHERE status='RESERVED'", Long.class));
    }

    @Test
    void durableDispatcherRejectsSecondNodeAndRetainsUnknownAfterProcessDeath() throws Exception {
        var source = source();
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("DROP TABLE IF EXISTS quant_order_dispatch_ticket");
        jdbc.execute("DROP TABLE IF EXISTS quant_live_dispatch_guard");
        jdbc.execute("""
            CREATE TABLE quant_live_dispatch_guard (
              credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              active_dispatch_key VARCHAR(160), updated_at DATETIME,
              PRIMARY KEY (credential_id,symbol)) ENGINE=InnoDB
            """);
        jdbc.execute("""
            CREATE TABLE quant_order_dispatch_ticket (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              idempotency_key VARCHAR(160) NOT NULL, operation_type VARCHAR(16),
              client_order_id VARCHAR(80), strategy_instance_id BIGINT, lease_epoch BIGINT,
              dispatch_status VARCHAR(32), exchange_order_id BIGINT,
              created_at DATETIME, updated_at DATETIME,
              UNIQUE KEY uk_ticket(credential_id,idempotency_key)) ENGINE=InnoDB
            """);
        var mapper = session(source, org.dromara.quant.execution.mapper.LiveOrderDispatchMapper.class)
            .getMapper(org.dromara.quant.execution.mapper.LiveOrderDispatchMapper.class);
        var txManager = new DataSourceTransactionManager(source);
        var first = new org.dromara.quant.execution.LiveOrderDispatcher(mapper, txManager);
        var second = new org.dromara.quant.execution.LiveOrderDispatcher(mapper, txManager);
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> {
                gate.await();
                try { return first.begin(1L, "BTCUSDT", "node1", "OPEN", "cid-node1", 10L, 4L); }
                catch (IllegalStateException blocked) { return null; }
            });
            var two = pool.submit(() -> {
                gate.await();
                try { return second.begin(1L, "BTCUSDT", "node2", "OPEN", "cid-node2", 20L, 5L); }
                catch (IllegalStateException blocked) { return null; }
            });
            gate.countDown();
            var ticket1 = one.get(15, TimeUnit.SECONDS);
            var ticket2 = two.get(15, TimeUnit.SECONDS);
            assertNotEquals(ticket1 == null, ticket2 == null);
            var winning = ticket1 == null ? ticket2 : ticket1;
            (ticket1 == null ? second : first).unknown(winning);
            assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quant_order_dispatch_ticket WHERE dispatch_status='UNKNOWN'", Long.class));
            assertThrows(IllegalStateException.class,
                () -> first.begin(1L, "BTCUSDT", "restarted", "OPEN", "cid-restart", 10L, 6L));
            assertFalse(first.recoveredFromExchange(1L, "BTCUSDT", winning.key(), "wrong-client", 990L));
            String expectedClient = ticket1 == null ? "cid-node2" : "cid-node1";
            assertTrue(second.recoveredFromExchange(1L, "BTCUSDT", winning.key(), expectedClient, 990L));
            var resumed = first.begin(1L, "BTCUSDT", "new-key", "OPEN", "cid-new", 10L, 6L);
            first.notSent(resumed);
        }
    }

    @Test
    void actualInnoDbFillUniquenessForbidsCompetingStrategyAttribution() throws Exception {
        var source = source();
        var db = new JdbcTemplate(source);
        db.execute("DROP TABLE IF EXISTS quant_strategy_fill_allocation");
        db.execute("""
            CREATE TABLE quant_strategy_fill_allocation (
              id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
              credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              exchange_trade_id BIGINT NOT NULL, exchange_order_id BIGINT NOT NULL,
              strategy_id BIGINT NOT NULL, strategy_instance_id BIGINT NOT NULL,
              side VARCHAR(8) NOT NULL, allocated_qty DECIMAL(38,16) NOT NULL,
              allocated_price DECIMAL(38,16) NOT NULL, allocated_fee DECIMAL(38,16) NOT NULL,
              realized_pnl DECIMAL(38,16) NOT NULL, trade_time DATETIME NOT NULL,
              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uk_strategy_exchange_fill(credential_id,symbol,exchange_trade_id)
            ) ENGINE=InnoDB
            """);
        var mapper = session(source, org.dromara.quant.execution.mapper.StrategyFillAllocationMapper.class)
            .getMapper(org.dromara.quant.execution.mapper.StrategyFillAllocationMapper.class);
        var allocations = new org.dromara.quant.execution.StrategyFillAllocationService(mapper);
        var order = new org.dromara.quant.order.domain.QuantOrder();
        order.setCredentialId(1L);
        order.setSymbol("BTCUSDT");
        order.setExchangeOrderId(500L);
        order.setStrategyId(10L);
        order.setStrategyInstanceId(20L);
        var fill = new org.dromara.quant.exchange.ExchangeGateway.TradeSnapshot(
            "BTCUSDT", 9007199254740993L, 500L, "BUY",
            new BigDecimal("1.25"), new BigDecimal("100"), new BigDecimal("0.01"),
            BigDecimal.ZERO, Instant.parse("2026-10-14T03:00:00Z"));
        allocations.allocate(fill, order, 1L);
        allocations.allocate(fill, order, 1L);
        order.setStrategyInstanceId(21L);
        assertThrows(IllegalStateException.class, () -> allocations.allocate(fill, order, 1L));
        assertEquals(1L, db.queryForObject(
            "SELECT COUNT(*) FROM quant_strategy_fill_allocation", Long.class));
    }

    @Test
    void concurrentMySqlOrderUpdatesCannotRegressFilledToNew() throws Exception {
        var source = source();
        var db = new JdbcTemplate(source);
        db.execute("DROP TABLE IF EXISTS quant_order");
        db.execute("""
            CREATE TABLE quant_order (
              id BIGINT PRIMARY KEY, credential_id BIGINT NOT NULL,
              strategy_id BIGINT, strategy_instance_id BIGINT,
              symbol VARCHAR(32), exchange_order_id BIGINT,
              client_order_id VARCHAR(120), side VARCHAR(8), order_type VARCHAR(24),
              status VARCHAR(32), position_side VARCHAR(8),
              price DECIMAL(30,12), original_quantity DECIMAL(30,12),
              executed_quantity DECIMAL(30,12), reduce_only BOOLEAN,
              updated_at DATETIME
            ) ENGINE=InnoDB
            """);
        db.update("""
            INSERT INTO quant_order (id,credential_id,strategy_id,strategy_instance_id,symbol,
              exchange_order_id,client_order_id,side,order_type,status,executed_quantity,updated_at)
            VALUES (1,1,10,20,'BTCUSDT',99,'cid-99','BUY','LIMIT','PARTIALLY_FILLED',0.7,NOW())
            """);
        var mapper = session(source, org.dromara.quant.order.mapper.QuantOrderMapper.class)
            .getMapper(org.dromara.quant.order.mapper.QuantOrderMapper.class);
        java.util.function.BiFunction<String,String,org.dromara.quant.order.domain.QuantOrder> fact = (status,qty) -> {
            var order = new org.dromara.quant.order.domain.QuantOrder();
            order.setId(1L);
            order.setCredentialId(1L);
            order.setStrategyId(10L);
            order.setStrategyInstanceId(20L);
            order.setSymbol("BTCUSDT");
            order.setExchangeOrderId(99L);
            order.setClientOrderId("cid-99");
            order.setSide("BUY");
            order.setOrderType("LIMIT");
            order.setStatus(status);
            order.setExecutedQuantity(new BigDecimal(qty));
            order.setUpdatedAt(java.time.LocalDateTime.now());
            return order;
        };
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var filled = pool.submit(() -> {
                start.await();
                return mapper.mergeExchangeFactIfMonotonic(fact.apply("FILLED","1"));
            });
            var lateNew = pool.submit(() -> {
                start.await();
                return mapper.mergeExchangeFactIfMonotonic(fact.apply("NEW","0.9"));
            });
            start.countDown();
            assertEquals(1, filled.get(15, TimeUnit.SECONDS));
            assertEquals(0, lateNew.get(15, TimeUnit.SECONDS));
        }
        assertEquals("FILLED", db.queryForObject("SELECT status FROM quant_order WHERE id=1", String.class));
        assertEquals(0, BigDecimal.ONE.compareTo(db.queryForObject(
            "SELECT executed_quantity FROM quant_order WHERE id=1", BigDecimal.class)));
    }

    private static OpenPositionCommand order(String symbol, String key) {
        return new OpenPositionCommand(1L, 10L, 100L, null, symbol, "BUY", "MARKET",
            BigDecimal.ONE, BigDecimal.TEN, null, key, "TESTNET", null, 1, "ISOLATED", null);
    }
}
