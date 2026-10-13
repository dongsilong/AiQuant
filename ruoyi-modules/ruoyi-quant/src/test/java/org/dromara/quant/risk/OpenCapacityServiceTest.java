package org.dromara.quant.risk;

import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.OpenPositionCommand;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class OpenCapacityServiceTest {
    @Test
    void fiveConcurrentSymbolsAcrossTwoNodesOnlyAdmitOneForStrategy() throws Exception {
        Fixture f = fixture(2, 1);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int number = i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        reserve(number % 2 == 0 ? f.first : f.second, command(1L, 10L, 100L + number, "S" + number, "entry" + number), BigDecimal.TEN, List.of());
                        return true;
                    } catch (OpenCapacityService.CapacityBlockedException blocked) { return false; }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) { if (result.get(5, TimeUnit.SECONDS)) { admitted++; } }
            assertEquals(1, admitted);
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_open_capacity WHERE status='RESERVED'", Integer.class));
        }
    }

    @Test
    void independentStrategiesShareAccountLimitAndAccountsRemainIndependent() {
        Fixture f = fixture(2, 1);
        reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "one"), BigDecimal.TEN, List.of());
        reserve(f.second, command(1L, 20L, 200L, "ETHUSDT", "two"), BigDecimal.TEN, List.of());
        var error = assertThrows(OpenCapacityService.CapacityBlockedException.class,
            () -> reserve(f.first, command(1L, 30L, 300L, "PUMPUSDT", "three"), BigDecimal.TEN, List.of()));
        assertTrue(error.getMessage().contains("账户最大持仓数"));
        reserve(f.first, command(2L, 10L, 400L, "BTCUSDT", "other-account"), BigDecimal.TEN, List.of());
    }

    @Test
    void pendingUnknownAndAcceptedOrdersNeverExpireOnEmptyPositionSnapshot() {
        for (String status : List.of("PENDING", "UNKNOWN", "CONFIRMED")) {
            Fixture f = fixture(2, 1);
            reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "first"), BigDecimal.TEN, List.of());
            f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'first',?, 'FILLED')", status);
            f.jdbc.update("UPDATE quant_open_capacity SET created_at=TIMESTAMP '2020-01-01 00:00:00'");
            f.first.releaseFailed(1L, "first");
            assertThrows(OpenCapacityService.CapacityBlockedException.class,
                () -> reserve(f.second, command(1L, 10L, 101L, "ETHUSDT", "next"), BigDecimal.TEN, List.of()));
        }
    }

    @Test
    void failedExecutionReleasesButUnknownDoesNot() {
        Fixture f = fixture(2, 1);
        reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "first"), BigDecimal.TEN, List.of());
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'first','FAILED',NULL)");
        f.first.releaseFailed(1L, "first");
        reserve(f.second, command(1L, 10L, 101L, "ETHUSDT", "next"), BigDecimal.TEN, List.of());
    }

    @Test
    void onlyConfirmedFullCloseReleasesSlotAndPartialCloseKeepsIt() {
        Fixture f = fixture(2, 1);
        reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "first"), BigDecimal.TEN, List.of());
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'first','CONFIRMED','FILLED')");
        f.jdbc.update("INSERT INTO quant_strategy_instance VALUES (100,10,1,1000)");
        f.jdbc.update("INSERT INTO quant_strategy_symbol VALUES (1000,10000)");
        f.jdbc.update("INSERT INTO quant_symbol VALUES (10000,'BTCUSDT')");
        f.jdbc.update("INSERT INTO quant_strategy_position_state (instance_id,status,position_qty,direction,updated_at) VALUES (100,'OPEN',1,'LONG',CURRENT_TIMESTAMP)");
        f.jdbc.update("INSERT INTO quant_position VALUES (1,'BTCUSDT','LONG',1)");
        assertThrows(OpenCapacityService.CapacityBlockedException.class,
            () -> reserve(f.second, command(1L, 10L, 101L, "ETHUSDT", "next"), BigDecimal.TEN, List.of()));
        f.jdbc.update("UPDATE quant_position SET quantity=0");
        f.jdbc.update("UPDATE quant_strategy_position_state SET status='CLOSED',position_qty=0,updated_at=TIMESTAMP '2099-01-01 00:00:00'");
        reserve(f.second, command(1L, 10L, 101L, "ETHUSDT", "next"), BigDecimal.TEN, List.of());
        assertEquals("RELEASED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity WHERE idempotency_key='first'", String.class));
    }

    @Test
    void exchangePositionsMissingLocallyStillConsumeAccountCapacity() {
        Fixture f = fixture(1, 1);
        var existing = new ExchangeGateway.PositionSnapshot("BTCUSDT", "BOTH", BigDecimal.ONE.negate(), BigDecimal.TEN, BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE, 1, null, null);
        assertThrows(OpenCapacityService.CapacityBlockedException.class,
            () -> reserve(f.first, command(1L, 10L, 101L, "ETHUSDT", "next"), BigDecimal.TEN, List.of(existing)));
    }

    @Test
    void absentMigrationOrUnknownAccountFactsFailClosed() {
        Fixture f = fixture(2, 1);
        assertThrows(OpenCapacityService.CapacityBlockedException.class,
            () -> reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "missing"), BigDecimal.TEN, null));
        f.jdbc.execute("DROP TABLE quant_open_capacity");
        assertThrows(org.springframework.dao.DataAccessException.class,
            () -> reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "schema-missing"), BigDecimal.TEN, List.of()));
    }

    @Test
    void nonPaperEntryWithoutTrustedBalanceFailsClosed() {
        Fixture f = fixture(3, 3);
        assertThrows(OpenCapacityService.CapacityBlockedException.class,
            () -> f.first.reserve(command(1L, 10L, 100L, "BTCUSDT", "no-balance"), BigDecimal.TEN, List.of()));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_open_capacity", Integer.class));
    }

    @Test
    void closedSlotsAreReleasedBeforePendingStateOverwriteAndStayReleasedAfterNextRejection() {
        Fixture f=fixture(4,4);
        closedSlot(f,"old-long","LONG","RECOVERED","FILLED");
        f.jdbc.update("INSERT INTO quant_open_capacity VALUES(1,'old-short',10,100,'BTCUSDT','SHORT',10,'RESERVED',TIMESTAMP '2020-01-01 00:00:00',CURRENT_TIMESTAMP)");
        f.jdbc.update("INSERT INTO quant_execution_record VALUES(1,'old-short','RECOVERED','FILLED')");
        var outer = new org.springframework.transaction.support.TransactionTemplate(
            new DataSourceTransactionManager(Objects.requireNonNull(f.jdbc.getDataSource())));
        outer.executeWithoutResult(status -> {
            cleanup(f, List.of());
            status.setRollbackOnly();
        });
        f.jdbc.update("UPDATE quant_strategy_position_state SET status='OPEN_PENDING',position_qty=23594,updated_at=CURRENT_TIMESTAMP");
        var tooLarge=command(1L,10L,100L,"BTCUSDT","new");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> reserve(f.first,tooLarge,new BigDecimal("2000"),List.of()));
        assertEquals(2,f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_open_capacity WHERE status='RELEASED'",Integer.class));
        reserve(f.first,command(1L,10L,100L,"BTCUSDT","next"),BigDecimal.TEN,List.of());
        assertEquals(1,f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_open_capacity WHERE status='RESERVED'",Integer.class));
    }

    @Test
    void cleanupNeverReleasesUnknownPartialOrUnclosedSlots() {
        for (String execution : List.of("PENDING", "UNKNOWN", "RECOVERED")) {
            for (String order : List.of("FILLED", "PARTIALLY_FILLED")) {
                if ("RECOVERED".equals(execution) && "FILLED".equals(order)) { continue; }
                Fixture f = fixture(4, 4);
                closedSlot(f, "old", "LONG", execution, order);
                cleanup(f, List.of());
                assertEquals("RESERVED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity", String.class));
            }
        }
        Fixture f=fixture(4,4);
        closedSlot(f,"old","LONG","RECOVERED","FILLED");
        f.jdbc.update("UPDATE quant_strategy_position_state SET status='OPEN_PENDING'");
        cleanup(f,List.of());
        assertEquals("RESERVED",f.jdbc.queryForObject("SELECT status FROM quant_open_capacity",String.class));
    }

    @Test
    void cleanupRequiresEmptyExchangeAndLocalPositionsAndNewerClose() {
        Fixture f=fixture(4,4);
        closedSlot(f,"old","LONG","RECOVERED","FILLED");
        var live=new ExchangeGateway.PositionSnapshot("BTCUSDT","LONG",BigDecimal.ONE,BigDecimal.TEN,BigDecimal.TEN,
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,1,null,null);
        cleanup(f,List.of(live));
        assertEquals("RESERVED",f.jdbc.queryForObject("SELECT status FROM quant_open_capacity",String.class));
        f.jdbc.update("INSERT INTO quant_position VALUES(1,'BTCUSDT','LONG',1)");
        cleanup(f,List.of());
        assertEquals("RESERVED",f.jdbc.queryForObject("SELECT status FROM quant_open_capacity",String.class));
        f.jdbc.update("UPDATE quant_position SET quantity=0");
        f.jdbc.update("UPDATE quant_strategy_position_state SET updated_at=TIMESTAMP '2019-01-01 00:00:00'");
        cleanup(f,List.of());
        assertEquals("RESERVED",f.jdbc.queryForObject("SELECT status FROM quant_open_capacity",String.class));
        assertThrows(RuntimeException.class,() -> cleanup(f,null));
    }

    @Test
    void closedStateWithPendingActionOrUnknownLocalQuantityCannotReleaseCapacity() {
        Fixture f = fixture(4, 4);
        closedSlot(f, "old", "LONG", "RECOVERED", "FILLED");
        f.jdbc.update("UPDATE quant_strategy_position_state SET pending_action='OPEN'");
        cleanup(f, List.of());
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity", String.class));
        f.jdbc.update("UPDATE quant_strategy_position_state SET pending_action=NULL");
        f.jdbc.update("INSERT INTO quant_position VALUES(1,'BTCUSDT','LONG',NULL)");
        cleanup(f, List.of());
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity", String.class));
    }

    private void closedSlot(Fixture f,String id,String direction,String execution,String order) {
        f.jdbc.update("INSERT INTO quant_strategy_instance VALUES(100,10,1,1000)");
        f.jdbc.update("INSERT INTO quant_strategy_symbol VALUES(1000,10000)");
        f.jdbc.update("INSERT INTO quant_symbol VALUES(10000,'BTCUSDT')");
        f.jdbc.update("INSERT INTO quant_strategy_position_state (instance_id,status,position_qty,direction,updated_at) VALUES(100,'CLOSED',23594,?,CURRENT_TIMESTAMP)",direction);
        f.jdbc.update("INSERT INTO quant_open_capacity VALUES(1,?,10,100,'BTCUSDT',?,10,'RESERVED',TIMESTAMP '2020-01-01 00:00:00',CURRENT_TIMESTAMP)",id,direction);
        f.jdbc.update("INSERT INTO quant_execution_record VALUES(1,?,?,?)",id,execution,order);
    }

    private void cleanup(Fixture f,List<ExchangeGateway.PositionSnapshot> positions) {
        f.first.releaseClosedForInstance(1L,100L,"BTCUSDT",positions);
    }

    private static org.dromara.quant.market.AccountBalanceSnapshot balance(Long id, String amount, Set<String> covered) {
        return new org.dromara.quant.market.AccountBalanceSnapshot(id, new BigDecimal(amount), covered, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
    }

    private static void reserve(OpenCapacityService service, OpenPositionCommand command, BigDecimal notional,
                                List<ExchangeGateway.PositionSnapshot> positions) {
        service.reserve(command, notional, positions, balance(command.credentialId(), "1000", Set.of()));
    }

    @Test
    void concurrentSymbolsAcrossTwoNodesCannotReuseSameFunds() throws Exception {
        Fixture f = fixture(10, 10);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                int number = i;
                results.add(executor.submit(() -> {
                    start.await();
                    try {
                        (number % 2 == 0 ? f.first : f.second).reserve(command(1L, 10L, 100L + number, "S" + number, "money" + number),
                            new BigDecimal("6"), List.of(), balance(1L, "10", Set.of()));
                        return true;
                    } catch (OpenCapacityService.CapacityBlockedException blocked) { return false; }
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) { if (result.get(5, TimeUnit.SECONDS)) { admitted++; } }
            assertEquals(1, admitted);
            assertEquals(new BigDecimal("6.0000000000000000"), f.jdbc.queryForObject("SELECT SUM(reserved_margin) FROM quant_open_capacity WHERE status='RESERVED'", BigDecimal.class));
        }
    }

    @Test
    void filledCoveredByNewSnapshotIsNotDeductedTwiceButUnknownStillReserves() {
        Fixture f = fixture(10, 10);
        f.first.reserve(command(1L, 10L, 100L, "BTCUSDT", "filled"), new BigDecimal("40"), List.of(), balance(1L, "100", Set.of()));
        assertEquals(0, new BigDecimal("60").compareTo(f.first.availableBalance(1L, balance(1L, "100", Set.of()))));
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'filled','CONFIRMED','FILLED')");
        var covered = f.first.balanceCoverage(1L);
        assertEquals(Set.of("filled"), covered);
        var newBalance = balance(1L, "60", covered);
        assertEquals(0, new BigDecimal("60").compareTo(f.first.availableBalance(1L, newBalance)));
        f.second.reserve(command(1L, 10L, 101L, "ETHUSDT", "unknown"), new BigDecimal("30"), List.of(), newBalance);
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'unknown','UNKNOWN','FILLED')");
        assertEquals(Set.of("filled"), f.first.balanceCoverage(1L));
        assertEquals(0, new BigDecimal("30").compareTo(f.first.availableBalance(1L, newBalance)));
        f.first.releaseFailed(1L, "unknown");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.first.reserve(
            command(1L, 10L, 102L, "PUMPUSDT", "third"), new BigDecimal("31"), List.of(), newBalance));
        f.jdbc.update("UPDATE quant_execution_record SET execution_status='FAILED' WHERE idempotency_key='unknown'");
        f.first.releaseFailed(1L, "unknown");
        assertEquals(0, new BigDecimal("60").compareTo(f.first.availableBalance(1L, newBalance)));
    }

    @Test
    void legacyUnknownMarginAndExpiredOrOtherAccountSnapshotsFailClosed() {
        Fixture f = fixture(10, 10);
        reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "legacy"), BigDecimal.TEN, List.of());
        f.jdbc.update("UPDATE quant_open_capacity SET reserved_margin=NULL");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.first.availableBalance(1L, balance(1L, "100", Set.of())));
        var command = command(1L, 10L, 101L, "ETHUSDT", "new");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.first.reserve(command, BigDecimal.TEN, List.of(), balance(2L, "100", Set.of())));
        var expired = new org.dromara.quant.market.AccountBalanceSnapshot(1L, BigDecimal.TEN, Set.of(), System.nanoTime() - 1);
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.first.reserve(command, BigDecimal.ONE, List.of(), expired));
    }

    @Test
    void absentMarginMigrationCannotCreateTrustedBalanceCoverage() {
        Fixture f = fixture(3, 3);
        f.jdbc.execute("ALTER TABLE quant_open_capacity DROP COLUMN reserved_margin");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> f.first.balanceCoverage(1L));
    }

    @Test
    void readOnlyRetryCheckDoesNotCreateRecordsAndRecognizesLateFailedExecution() {
        Fixture f = fixture(1, 1);
        reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "old"), BigDecimal.TEN, List.of());
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'old','UNKNOWN','NEW')");
        var candidate = command(1L, 10L, 101L, "ETHUSDT", "retry");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.second.requireCanReserve(candidate,
            BigDecimal.TEN, List.of(), balance(1L, "10", Set.of())));
        f.jdbc.update("UPDATE quant_execution_record SET execution_status='FAILED' WHERE idempotency_key='old'");
        assertDoesNotThrow(() -> f.second.requireCanReserve(candidate, BigDecimal.TEN, List.of(), balance(1L, "10", Set.of())));
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity WHERE idempotency_key='old'", String.class));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_open_capacity", Integer.class));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_execution_record", Integer.class));
        f.second.reserve(candidate, BigDecimal.TEN, List.of(), balance(1L, "10", Set.of()));
        assertEquals("RELEASED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity WHERE idempotency_key='old'", String.class));
    }

    @Test
    void readOnlyRetryRecognizesConfirmedClosedSlotWithoutDeductingItsMargin() {
        Fixture f = fixture(1, 1);
        reserve(f.first, command(1L, 10L, 100L, "BTCUSDT", "old"), BigDecimal.TEN, List.of());
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'old','RECOVERED','FILLED')");
        f.jdbc.update("INSERT INTO quant_strategy_instance VALUES (100,10,1,1000)");
        f.jdbc.update("INSERT INTO quant_strategy_position_state VALUES (100,'CLOSED',0,'LONG',DATEADD('SECOND',1,CURRENT_TIMESTAMP),NULL)");
        var candidate = command(1L, 10L, 101L, "ETHUSDT", "retry");
        assertDoesNotThrow(() -> f.second.requireCanReserve(candidate, BigDecimal.TEN, List.of(), balance(1L, "10", Set.of())));
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity WHERE idempotency_key='old'", String.class));
        f.second.reserve(candidate, BigDecimal.TEN, List.of(), balance(1L, "10", Set.of()));
        assertEquals("RELEASED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity WHERE idempotency_key='old'", String.class));
    }

    @Test
    void readOnlyRetryRejectsUnknownAndInsufficientFundsButAcceptsRefreshedBalance() {
        Fixture f = fixture(10, 10);
        var candidate = command(1L, 10L, 100L, "BTCUSDT", "retry");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.first.requireCanReserve(candidate,
            new BigDecimal("20"), List.of(), balance(1L, "10", Set.of())));
        assertDoesNotThrow(() -> f.first.requireCanReserve(candidate, new BigDecimal("20"), List.of(), balance(1L, "30", Set.of())));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM quant_open_capacity", Integer.class));
        reserve(f.first, candidate, BigDecimal.TEN, List.of());
        f.jdbc.update("INSERT INTO quant_execution_record VALUES (1,'retry','UNKNOWN','FILLED')");
        assertThrows(OpenCapacityService.CapacityBlockedException.class, () -> f.second.requireCanReserve(
            command(1L, 10L, 101L, "BTCUSDT", "new"), BigDecimal.ONE, List.of(), balance(1L, "100", Set.of())));
        assertEquals("RESERVED", f.jdbc.queryForObject("SELECT status FROM quant_open_capacity WHERE idempotency_key='retry'", String.class));
    }

    private static OpenPositionCommand command(Long credential, Long strategy, Long instance, String symbol, String id) {
        return new OpenPositionCommand(credential, strategy, instance, null, symbol, "BUY", "MARKET", BigDecimal.ONE, BigDecimal.TEN,
            null, id, "TESTNET", null, 1, "ISOLATED", null);
    }

    private Fixture fixture(int accountLimit, int strategyLimit) {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE quant_credential (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE quant_risk_config (credential_id BIGINT, max_open_positions INT)");
        jdbc.execute("CREATE TABLE quant_strategy (id BIGINT PRIMARY KEY, max_open_count INT)");
        jdbc.execute("CREATE TABLE quant_position (credential_id BIGINT, symbol VARCHAR(32), position_side VARCHAR(8), quantity DECIMAL(30,16))");
        jdbc.execute("CREATE TABLE quant_strategy_instance (id BIGINT, strategy_id BIGINT, credential_id BIGINT, strategy_symbol_id BIGINT)");
        jdbc.execute("CREATE TABLE quant_strategy_symbol (id BIGINT, symbol_id BIGINT)");
        jdbc.execute("CREATE TABLE quant_symbol (id BIGINT, symbol VARCHAR(32))");
        jdbc.execute("CREATE TABLE quant_strategy_position_state (instance_id BIGINT, status VARCHAR(32), position_qty DECIMAL(30,16), direction VARCHAR(8), updated_at TIMESTAMP, pending_action VARCHAR(32))");
        jdbc.execute("CREATE TABLE quant_execution_record (credential_id BIGINT, idempotency_key VARCHAR(160), execution_status VARCHAR(16), exchange_order_status VARCHAR(32))");
        jdbc.execute("CREATE TABLE quant_open_capacity (credential_id BIGINT, idempotency_key VARCHAR(160), strategy_id BIGINT, instance_id BIGINT, symbol VARCHAR(32), "
            + "position_side VARCHAR(8), reserved_margin DECIMAL(38,16), status VARCHAR(16), created_at TIMESTAMP, updated_at TIMESTAMP, PRIMARY KEY(credential_id,idempotency_key))");
        jdbc.update("INSERT INTO quant_credential VALUES (1),(2)");
        jdbc.update("INSERT INTO quant_risk_config VALUES (1,?),(2,?)", accountLimit, accountLimit);
        jdbc.update("INSERT INTO quant_strategy VALUES (10,?),(20,?),(30,?)", strategyLimit, strategyLimit, strategyLimit);
        var risk = mock(RiskDecisionService.class);
        // 使用 MyBatis-Spring 的 SqlSessionTemplate 验证 Mapper 与 DataSourceTransactionManager 共享事务。
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.addMapper(org.dromara.quant.risk.mapper.OpenCapacityMapper.class);
        var factoryBean = new org.mybatis.spring.SqlSessionFactoryBean();
        factoryBean.setDataSource(source);
        factoryBean.setConfiguration(configuration);
        try {
            var factory = Objects.requireNonNull(factoryBean.getObject());
            var session = new org.mybatis.spring.SqlSessionTemplate(factory);
            var mapper = session.getMapper(org.dromara.quant.risk.mapper.OpenCapacityMapper.class);
            return new Fixture(jdbc, new OpenCapacityService(mapper, new DataSourceTransactionManager(source), risk),
                new OpenCapacityService(mapper, new DataSourceTransactionManager(source), risk));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot initialize transactional MyBatis capacity fixture", ex);
        }
    }

    private record Fixture(JdbcTemplate jdbc, OpenCapacityService first, OpenCapacityService second) {}
}
