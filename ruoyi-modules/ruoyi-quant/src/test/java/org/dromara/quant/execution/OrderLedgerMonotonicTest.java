package org.dromara.quant.execution;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class OrderLedgerMonotonicTest {
    private record Fixture(QuantOrderMapper mapper, JdbcTemplate jdbc) {}
    private Fixture fixture() throws Exception {
        var ds = new DriverManagerDataSource(
            "jdbc:h2:mem:oms_order_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            CREATE TABLE quant_order (
              id BIGINT PRIMARY KEY,
              credential_id BIGINT NOT NULL, strategy_id BIGINT,
              strategy_instance_id BIGINT, symbol VARCHAR(40),
              exchange_order_id BIGINT, client_order_id VARCHAR(128),
              side VARCHAR(8), order_type VARCHAR(24), status VARCHAR(32),
              position_side VARCHAR(8), price DECIMAL(30,12),
              original_quantity DECIMAL(30,12), executed_quantity DECIMAL(30,12),
              reduce_only BOOLEAN, updated_at TIMESTAMP
            )
            """);
        jdbc.update("""
            INSERT INTO quant_order (id,credential_id,strategy_id,strategy_instance_id,
              symbol,exchange_order_id,client_order_id,side,order_type,status,
              executed_quantity,updated_at)
            VALUES (1,1,10,20,'BTCUSDT',99,'cid-99','BUY','LIMIT',
              'PARTIALLY_FILLED',0.7,CURRENT_TIMESTAMP)
            """);
        var config = new Configuration();
        config.addMapper(QuantOrderMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
            .getMapper(QuantOrderMapper.class);
        return new Fixture(mapper,jdbc);
    }
    private QuantOrder fact(String status, String filled) {
        var x = new QuantOrder();
        x.setId(1L);
        x.setCredentialId(1L);
        x.setStrategyId(10L);
        x.setStrategyInstanceId(20L);
        x.setSymbol("BTCUSDT");
        x.setExchangeOrderId(99L);
        x.setClientOrderId("cid-99");
        x.setSide("BUY");
        x.setOrderType("LIMIT");
        x.setStatus(status);
        x.setExecutedQuantity(new BigDecimal(filled));
        x.setOriginalQuantity(BigDecimal.ONE);
        x.setUpdatedAt(LocalDateTime.now());
        return x;
    }
    @Test
    void staleNewFactCannotDecreasePartiallyFilledQuantity() throws Exception {
        var f=fixture();
        assertEquals(0,f.mapper().mergeExchangeFactIfMonotonic(fact("NEW","0")));
        assertEquals("PARTIALLY_FILLED",f.jdbc().queryForObject(
            "SELECT status FROM quant_order WHERE id=1",String.class));
        assertEquals(0,new BigDecimal("0.7").compareTo(f.jdbc().queryForObject(
            "SELECT executed_quantity FROM quant_order WHERE id=1",BigDecimal.class)));
    }

    @Test
    void terminalCancellationAfterPartialFillIsPermanent() throws Exception {
        var f=fixture();
        assertEquals(1,f.mapper().mergeExchangeFactIfMonotonic(fact("CANCELED","0.7")));
        assertEquals(0,f.mapper().mergeExchangeFactIfMonotonic(fact("NEW","0.7")));
        assertEquals(0,f.mapper().mergeExchangeFactIfMonotonic(fact("PARTIALLY_FILLED","0.7")));
        assertEquals("CANCELED",f.jdbc().queryForObject("SELECT status FROM quant_order",String.class));
    }

    @Test
    void newerPartialFillCanIncreaseQuantityButNotDecreaseIt() throws Exception {
        var f=fixture();
        assertEquals(1,f.mapper().mergeExchangeFactIfMonotonic(fact("PARTIALLY_FILLED","0.9")));
        assertEquals(0,f.mapper().mergeExchangeFactIfMonotonic(fact("PARTIALLY_FILLED","0.8")));
        assertEquals(0,new BigDecimal("0.9").compareTo(f.jdbc().queryForObject(
            "SELECT executed_quantity FROM quant_order",BigDecimal.class)));
    }

    @Test
    void strategyOrderIdentityCannotBeReassignedByAnotherReconciler() throws Exception {
        var f=fixture();
        var other=fact("FILLED","1");
        other.setStrategyInstanceId(21L);
        assertEquals(0,f.mapper().mergeExchangeFactIfMonotonic(other));
        var wrongExchange=fact("FILLED","1");
        wrongExchange.setExchangeOrderId(100L);
        assertEquals(0,f.mapper().mergeExchangeFactIfMonotonic(wrongExchange));
        assertEquals(20L,f.jdbc().queryForObject(
            "SELECT strategy_instance_id FROM quant_order",Long.class));
    }
}
