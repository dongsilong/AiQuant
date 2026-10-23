package org.dromara.quant.execution;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.execution.mapper.LiveOrderDispatchMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class LiveDispatchRecoveryGuardSqlTest {
    @Test
    void recoverOnlyWhenTicketStillOwnsActiveAccountSymbolSlot() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:live_recovery_guard_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = new JdbcTemplate(data); // Test fixture only; production uses MyBatis mapper.
        db.execute("""
            CREATE TABLE quant_live_dispatch_guard (
              credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              active_dispatch_key VARCHAR(160), updated_at TIMESTAMP,
              PRIMARY KEY(credential_id,symbol))
            """);
        db.execute("""
            CREATE TABLE quant_order_dispatch_ticket (
              credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              idempotency_key VARCHAR(160) NOT NULL, client_order_id VARCHAR(80),
              operation_type VARCHAR(32), dispatch_status VARCHAR(32),
              exchange_order_id BIGINT, updated_at TIMESTAMP,
              PRIMARY KEY(credential_id,idempotency_key))
            """);
        db.update("INSERT INTO quant_live_dispatch_guard VALUES (11,'BTCUSDT','other-key',CURRENT_TIMESTAMP)");
        db.update("""
            INSERT INTO quant_order_dispatch_ticket VALUES
            (11,'BTCUSDT','key1','cid1','OPEN','UNKNOWN',NULL,CURRENT_TIMESTAMP)
            """);
        var cfg = new Configuration();
        cfg.addMapper(LiveOrderDispatchMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setConfiguration(cfg);
        factory.setDataSource(data);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(LiveOrderDispatchMapper.class);

        assertEquals(0, mapper.recoverVerified(11L, "BTCUSDT", "key1", "cid1", 123L));
        assertEquals("UNKNOWN", mapper.ticketStatus(11L, "key1"));

        db.update("""
            UPDATE quant_live_dispatch_guard
            SET active_dispatch_key='key1' WHERE credential_id=11 AND symbol='BTCUSDT'
            """);
        assertEquals(1, mapper.recoverVerified(11L, "BTCUSDT", "key1", "cid1", 123L));
        assertEquals("RECOVERED", mapper.ticketStatus(11L, "key1"));
        assertEquals(123L, db.queryForObject("SELECT exchange_order_id FROM quant_order_dispatch_ticket WHERE credential_id=11 AND idempotency_key='key1'", Long.class));
    }
}
