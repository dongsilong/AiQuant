package org.dromara.quant.risk;

import org.dromara.quant.execution.OpenPositionCommand;
import org.dromara.quant.market.AccountBalanceSnapshot;
import org.dromara.quant.risk.mapper.OpenCapacityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL 8.4 capacity admission race: two independent service instances,
 * two physical connection pools, one shared InnoDB account lock and slot ledger.
 * Not part of H2/dev suite; executed by mysql-integration CI with env credentials. */
class MySqlLiveCapacityRaceTest {
    @Test
    void lastSlotCannotBeReservedByBothNodes() throws Exception {
        String base = System.getenv("AIQUANT_MYSQL_URL");
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("MySQL integration environment required");
        }
        String username = System.getenv("AIQUANT_MYSQL_USERNAME");
        String password = System.getenv("AIQUANT_MYSQL_PASSWORD");
        var root = new JdbcTemplate(new DriverManagerDataSource(base, username, password));
        String schema = "quant_capacity_" + UUID.randomUUID().toString().replace("-", "");
        root.execute("CREATE DATABASE `" + schema + "`");
        String url = base.replaceFirst("/[^/?]+\\?", "/" + schema + "?");
        if (url.equals(base)) {
            root.execute("DROP DATABASE `" + schema + "`");
            throw new IllegalStateException("Cannot rewrite MySQL JDBC database URL");
        }
        var dsA = new DriverManagerDataSource(url, username, password);
        var dsB = new DriverManagerDataSource(url, username, password);
        var jdbc = new JdbcTemplate(dsA);
        try {
            jdbc.execute("CREATE TABLE quant_credential (id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_risk_config (credential_id BIGINT, max_open_positions INT) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_strategy (id BIGINT PRIMARY KEY, max_open_count INT) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_position (credential_id BIGINT, symbol VARCHAR(32), position_side VARCHAR(8), quantity DECIMAL(30,16)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_strategy_instance (id BIGINT, strategy_id BIGINT, credential_id BIGINT, strategy_symbol_id BIGINT) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_strategy_symbol (id BIGINT, symbol_id BIGINT) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_symbol (id BIGINT, symbol VARCHAR(32)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_strategy_position_state (instance_id BIGINT, status VARCHAR(32), position_qty DECIMAL(30,16), direction VARCHAR(8), updated_at DATETIME, pending_action VARCHAR(32)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_execution_record (credential_id BIGINT, idempotency_key VARCHAR(160), execution_status VARCHAR(16), exchange_order_status VARCHAR(32)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE quant_open_capacity (credential_id BIGINT, idempotency_key VARCHAR(160), strategy_id BIGINT, instance_id BIGINT, symbol VARCHAR(32), position_side VARCHAR(8), reserved_margin DECIMAL(38,16), status VARCHAR(16), created_at DATETIME, updated_at DATETIME, PRIMARY KEY(credential_id,idempotency_key)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO quant_credential VALUES (1)");
            jdbc.update("INSERT INTO quant_risk_config VALUES (1,1)");
            jdbc.update("INSERT INTO quant_strategy VALUES (10,1)");

            var risk = mock(RiskDecisionService.class);
            var first = service(dsA, risk);
            var second = service(dsB, risk);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                Future<Boolean> one = executor.submit(() -> attempt(first, "BTCUSDT", "node-a", start));
                Future<Boolean> two = executor.submit(() -> attempt(second, "ETHUSDT", "node-b", start));
                start.countDown();
                assertEquals(1, (one.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (two.get(20, TimeUnit.SECONDS) ? 1 : 0));
            }
            assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM quant_open_capacity WHERE status='RESERVED'", Integer.class));
        } finally {
            root.execute("DROP DATABASE `" + schema + "`");
        }
    }

    private static boolean attempt(OpenCapacityService service, String symbol, String key, CountDownLatch start)
        throws InterruptedException {
        start.await();
        var command = new OpenPositionCommand(1L, 10L, null, null, symbol,
            "BUY", "MARKET", BigDecimal.ONE, BigDecimal.TEN, null, key,
            "TESTNET", null, 1, "ISOLATED", null);
        try {
            service.reserve(command, BigDecimal.TEN, List.of(),
                new AccountBalanceSnapshot(1L, new BigDecimal("100"), Set.of(), System.nanoTime() + TimeUnit.MINUTES.toNanos(1)));
            return true;
        } catch (OpenCapacityService.CapacityBlockedException expected) {
            return false;
        }
    }

    private static OpenCapacityService service(DriverManagerDataSource ds, RiskDecisionService risk)
        throws Exception {
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(false);
        config.addMapper(OpenCapacityMapper.class);
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        bean.setConfiguration(config);
        var mapper = new SqlSessionTemplate(java.util.Objects.requireNonNull(bean.getObject()))
            .getMapper(OpenCapacityMapper.class);
        return new OpenCapacityService(mapper, new DataSourceTransactionManager(ds), risk);
    }
}
