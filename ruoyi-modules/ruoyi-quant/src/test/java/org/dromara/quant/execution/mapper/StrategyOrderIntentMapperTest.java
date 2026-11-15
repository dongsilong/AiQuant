package org.dromara.quant.execution.mapper;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.dromara.quant.execution.StrategyOrderIntentMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class StrategyOrderIntentMapperTest {
    @Test
    void bindsBothPositionSideWithoutOverwritingExchangeFact() throws Exception {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:strategy_order_intent_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            CREATE TABLE quant_order (
              id BIGINT PRIMARY KEY, credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              exchange_order_id BIGINT NOT NULL, client_order_id VARCHAR(80) NOT NULL,
              strategy_id BIGINT, strategy_instance_id BIGINT, position_side VARCHAR(20),
              reduce_only BOOLEAN
            )
            """);

        var configuration = new Configuration();
        String resource = "mapper/quant/StrategyOrderIntentMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
            .getMapper(StrategyOrderIntentMapper.class);

        insertOrder(jdbc, 1L, "BOTH");
        insertOrder(jdbc, 2L, "BOTH");
        insertOrder(jdbc, 3L, "LONG");
        insertOrder(jdbc, 4L, "SHORT");

        assertEquals(1, mapper.bindRecoveredOrder(1L, 10L, "BTCUSDT", 101L, "cid-1", 20L, 30L, "LONG", false));
        assertEquals(1, mapper.countExactRecoveredBinding(1L, 10L, "BTCUSDT", 101L, "cid-1", 20L, 30L, "LONG", false));
        assertEquals("BOTH", jdbc.queryForObject("SELECT position_side FROM quant_order WHERE id=1", String.class));

        assertEquals(1, mapper.bindRecoveredOrder(2L, 10L, "BTCUSDT", 102L, "cid-2", 20L, 30L, "SHORT", false));
        assertEquals(1, mapper.countExactRecoveredBinding(2L, 10L, "BTCUSDT", 102L, "cid-2", 20L, 30L, "SHORT", false));
        assertEquals("BOTH", jdbc.queryForObject("SELECT position_side FROM quant_order WHERE id=2", String.class));

        assertEquals(0, mapper.bindRecoveredOrder(3L, 10L, "BTCUSDT", 103L, "cid-3", 20L, 30L, "SHORT", false));
        assertEquals(0, mapper.countExactRecoveredBinding(3L, 10L, "BTCUSDT", 103L, "cid-3", 20L, 30L, "SHORT", false));
        assertEquals(0, mapper.bindRecoveredOrder(4L, 10L, "BTCUSDT", 104L, "cid-4", 20L, 30L, "LONG", false));
        assertEquals(0, mapper.countExactRecoveredBinding(4L, 10L, "BTCUSDT", 104L, "cid-4", 20L, 30L, "LONG", false));
    }

    private void insertOrder(JdbcTemplate jdbc, long id, String positionSide) {
        jdbc.update("""
            INSERT INTO quant_order
              (id, credential_id, symbol, exchange_order_id, client_order_id, position_side, reduce_only)
            VALUES (?, 10, 'BTCUSDT', ?, ?, ?, FALSE)
            """, id, 100L + id, "cid-" + id, positionSide);
    }
}
