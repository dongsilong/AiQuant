package org.dromara.quant.protection;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.protection.mapper.QuantProtectionOrderMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProtectionOrderOwnershipMapperTest {
    @Test
    void onlyExactPositionCycleCanBindMissingOwnerAndExistingOwnerCannotBeOverwritten() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:protection_owner_" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE quant_strategy_instance(id BIGINT,strategy_id BIGINT,strategy_symbol_id BIGINT,credential_id BIGINT)");
        jdbc.execute("CREATE TABLE quant_strategy_symbol(id BIGINT,symbol_id BIGINT)");
        jdbc.execute("CREATE TABLE quant_symbol(id BIGINT,symbol VARCHAR(32))");
        jdbc.execute("CREATE TABLE quant_strategy_position_state(instance_id BIGINT,position_id BIGINT,position_cycle_key VARCHAR(64),direction VARCHAR(10))");
        jdbc.execute("CREATE TABLE quant_protection_order(id BIGINT PRIMARY KEY,credential_id BIGINT,position_cycle_key VARCHAR(64),strategy_id BIGINT,strategy_instance_id BIGINT)");
        jdbc.execute("INSERT INTO quant_strategy_instance VALUES(3,2,4,1)");
        jdbc.execute("INSERT INTO quant_strategy_symbol VALUES(4,5)");
        jdbc.execute("INSERT INTO quant_symbol VALUES(5,'BTCUSDT')");
        jdbc.execute("INSERT INTO quant_strategy_position_state VALUES(3,51,'cycle-a','LONG')");
        jdbc.execute("INSERT INTO quant_protection_order VALUES(71,1,'cycle-a',NULL,NULL)");
        var configuration = new Configuration();
        configuration.addMapper(QuantProtectionOrderMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(QuantProtectionOrderMapper.class);

        assertEquals(1, mapper.cycleOwners(1L, 51L, "cycle-a", "BTCUSDT", "LONG").size());
        assertTrue(mapper.cycleOwners(1L, 51L, "cycle-b", "BTCUSDT", "LONG").isEmpty());
        assertTrue(mapper.cycleOwners(9L, 51L, "cycle-a", "BTCUSDT", "LONG").isEmpty());
        assertEquals(0, mapper.bindCycleOwner(71L, 1L, "cycle-b", 2L, 3L));
        assertEquals(1, mapper.bindCycleOwner(71L, 1L, "cycle-a", 2L, 3L));
        assertEquals(0, mapper.bindCycleOwner(71L, 1L, "cycle-a", 8L, 9L));
        assertEquals(3L, jdbc.queryForObject("SELECT strategy_instance_id FROM quant_protection_order WHERE id=71", Long.class));
    }
}
