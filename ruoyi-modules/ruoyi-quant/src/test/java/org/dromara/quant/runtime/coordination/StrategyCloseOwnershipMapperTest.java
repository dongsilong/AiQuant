package org.dromara.quant.runtime.coordination;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.runtime.coordination.mapper.StrategyCloseOwnershipMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class StrategyCloseOwnershipMapperTest {
    @Test
    void databaseJoinRejectsForeignAccountStrategyAndSymbol() throws Exception {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:close_owner_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE quant_strategy_instance(id BIGINT,credential_id BIGINT,strategy_id BIGINT,strategy_symbol_id BIGINT)");
        jdbc.execute("CREATE TABLE quant_strategy_symbol(id BIGINT,symbol_id BIGINT)");
        jdbc.execute("CREATE TABLE quant_symbol(id BIGINT,symbol VARCHAR(32))");
        jdbc.execute("""
            CREATE TABLE quant_strategy_position_state(
              instance_id BIGINT,status VARCHAR(32),direction VARCHAR(8),
              position_qty DECIMAL(30,16),pending_action VARCHAR(32),
              pending_idempotency_key VARCHAR(160),unsafe_recovery INT)
            """);
        jdbc.update("INSERT INTO quant_strategy_instance VALUES(13,11,12,15)");
        jdbc.update("INSERT INTO quant_strategy_symbol VALUES(15,17)");
        jdbc.update("INSERT INTO quant_symbol VALUES(17,'BTCUSDT')");
        jdbc.update("""
            INSERT INTO quant_strategy_position_state
              VALUES(13,'EXIT_PENDING','LONG',1.25,'HARD_STOP','exit-1',0)
            """);

        var config = new Configuration();
        config.addMapper(StrategyCloseOwnershipMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
            .getMapper(StrategyCloseOwnershipMapper.class);
        var guard = new StrategyCloseOwnershipGuard(mapper);

        guard.requireOwnedClose(11L, 12L, 13L, "BTCUSDT", "SELL", new BigDecimal("1.25"), "exit-1");
        assertThrows(IllegalStateException.class, () ->
            guard.requireOwnedClose(99L, 12L, 13L, "BTCUSDT", "SELL", BigDecimal.ONE, "exit-1"));
        assertThrows(IllegalStateException.class, () ->
            guard.requireOwnedClose(11L, 99L, 13L, "BTCUSDT", "SELL", BigDecimal.ONE, "exit-1"));
        assertThrows(IllegalStateException.class, () ->
            guard.requireOwnedClose(11L, 12L, 13L, "ETHUSDT", "SELL", BigDecimal.ONE, "exit-1"));
    }
}
