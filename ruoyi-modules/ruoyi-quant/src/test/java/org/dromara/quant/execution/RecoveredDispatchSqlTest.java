package org.dromara.quant.execution;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class RecoveredDispatchSqlTest {
    @Test
    void onlyExactRecoveredExecutionHoldingMatchingDispatchSlotIsRevisited() throws Exception {
        var data = new DriverManagerDataSource("jdbc:h2:mem:stranded_dispatch_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var db = new JdbcTemplate(data);
        db.execute("""
            CREATE TABLE quant_execution_record (
              id BIGINT PRIMARY KEY, credential_id BIGINT, symbol VARCHAR(32),
              idempotency_key VARCHAR(160), client_order_id VARCHAR(80),
              exchange_order_id BIGINT, operation_type VARCHAR(16),
              execution_status VARCHAR(24))
            """);
        db.execute("""
            CREATE TABLE quant_live_dispatch_guard (
              credential_id BIGINT, symbol VARCHAR(32),
              active_dispatch_key VARCHAR(160), updated_at TIMESTAMP)
            """);
        db.execute("""
            CREATE TABLE quant_order_dispatch_ticket (
              credential_id BIGINT, symbol VARCHAR(32),
              idempotency_key VARCHAR(160), client_order_id VARCHAR(80),
              operation_type VARCHAR(16), dispatch_status VARCHAR(24))
            """);
        db.update("""
            INSERT INTO quant_execution_record VALUES
              (1,11,'BTCUSDT','key1','cid1',9007199254740993,'OPEN','RECOVERED'),
              (2,11,'ETHUSDT','key2','cid2',55,'OPEN','RECOVERED'),
              (3,12,'BTCUSDT','key3','cid3',56,'CANCEL','RECOVERED'),
              (4,11,'SOLUSDT','key4','cid4',57,'OPEN','UNKNOWN')
            """);
        db.update("""
            INSERT INTO quant_live_dispatch_guard VALUES
              (11,'BTCUSDT','key1',CURRENT_TIMESTAMP),
              (11,'ETHUSDT','other-key',CURRENT_TIMESTAMP),
              (12,'BTCUSDT','key3',CURRENT_TIMESTAMP),
              (11,'SOLUSDT','key4',CURRENT_TIMESTAMP)
            """);
        db.update("""
            INSERT INTO quant_order_dispatch_ticket VALUES
              (11,'BTCUSDT','key1','cid1','OPEN','UNKNOWN'),
              (11,'ETHUSDT','key2','cid2','OPEN','UNKNOWN'),
              (12,'BTCUSDT','key3','cid3','CANCEL','ACKED'),
              (11,'SOLUSDT','key4','cid4','OPEN','UNKNOWN')
            """);
        var cfg = new Configuration();
        cfg.addMapper(QuantExecutionRecordMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setConfiguration(cfg);
        factory.setDataSource(data);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(QuantExecutionRecordMapper.class);
        var records = mapper.strandedRecoveredDispatches();
        assertEquals(1, records.size());
        assertEquals(1L, records.getFirst().getId());
        assertEquals(11L, records.getFirst().getCredentialId());
        assertEquals("BTCUSDT", records.getFirst().getSymbol());
        assertEquals("cid1", records.getFirst().getClientOrderId());
        assertEquals(9007199254740993L, records.getFirst().getExchangeOrderId());
    }

    @Test
    void recoveredExecutionSqlRejectsStaleTerminalStatusRegression() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("mapper/quant/QuantExecutionRecordMapper.xml")) {
            assertNotNull(stream, "execution record mapper XML must be available to the test");
            String xml = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String start = "<update id=\"markRecoveredAfterFact\"";
            int from = xml.indexOf(start);
            int to = xml.indexOf("</update>", from);
            assertTrue(from >= 0 && to > from, "recovery SQL statement must exist");
            String sql = xml.substring(from, to);
            assertTrue(sql.contains("execution_status <> 'RECOVERED'"), sql);
            assertTrue(sql.contains("exchange_order_status NOT IN ('FILLED','CANCELED','EXPIRED','REJECTED')"), sql);
            assertTrue(sql.contains("OR exchange_order_status=#{exchangeStatus}"), sql);
        }
    }
}
