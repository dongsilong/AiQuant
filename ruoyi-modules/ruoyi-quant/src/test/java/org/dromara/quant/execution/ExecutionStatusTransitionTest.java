package org.dromara.quant.execution;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ExecutionStatusTransitionTest {
    private record TestDb(JdbcTemplate jdbc, QuantExecutionRecordMapper mapper) {}

    private TestDb db() throws Exception {
        var source = new DriverManagerDataSource(
            "jdbc:h2:mem:execution_cas_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            CREATE TABLE quant_execution_record (
              id BIGINT PRIMARY KEY, operation_type VARCHAR(20), execution_status VARCHAR(24) NOT NULL,
              exchange_order_id BIGINT, exchange_order_status VARCHAR(36),
              error_message VARCHAR(255), recovery_attempts INT,
              last_recovery_at TIMESTAMP, updated_at TIMESTAMP)
            """);
        var config = new Configuration();
        config.addMapper(QuantExecutionRecordMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        return new TestDb(jdbc, new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
            .getMapper(QuantExecutionRecordMapper.class));
    }

    @Test
    void aRecoveredOrderCannotBeDowngradedByStaleFailureOrTimeout() throws Exception {
        var db = db();
        db.jdbc.update("INSERT INTO quant_execution_record(id, execution_status) VALUES (1,'UNKNOWN')");
        var now = LocalDateTime.now();
        assertEquals(1, db.mapper.markRecoveredAfterFact(1L, 99L, "FILLED", now));
        assertEquals(0, db.mapper.markUnknownIfUnresolved(1L, "late timeout", now));
        assertEquals(0, db.mapper.markFailedBeforeSend(1L, "late rejection", now));
        assertEquals(0, db.mapper.markAccepted(1L, 100L, "NEW", now));
        assertEquals("RECOVERED", db.jdbc.queryForObject(
            "SELECT execution_status FROM quant_execution_record WHERE id=1", String.class));
        assertEquals(99L, db.jdbc.queryForObject(
            "SELECT exchange_order_id FROM quant_execution_record WHERE id=1", Long.class));
    }

    @Test
    void onlyUnresolvedOrdersMayBecomeUnknownOrFail() throws Exception {
        var db = db();
        db.jdbc.update("INSERT INTO quant_execution_record(id, execution_status) VALUES (1,'PENDING'),(2,'PENDING')");
        var now = LocalDateTime.now();
        assertEquals(1, db.mapper.markUnknownIfUnresolved(1L, "http timeout", now));
        assertEquals(1, db.mapper.markAccepted(1L, 77L, "FILLED", now));
        assertEquals(0, db.mapper.markUnknownIfUnresolved(1L, "stale timeout", now));
        assertEquals(1, db.mapper.markFailedBeforeSend(2L, "not sent", now));
        assertEquals(0, db.mapper.markRecoveredAfterFact(2L, 77L, "FILLED", now));
        assertEquals(0, db.mapper.markAccepted(2L, 77L, "NEW", now));
        assertEquals("CONFIRMED", db.jdbc.queryForObject(
            "SELECT execution_status FROM quant_execution_record WHERE id=1", String.class));
        assertEquals("FAILED", db.jdbc.queryForObject(
            "SELECT execution_status FROM quant_execution_record WHERE id=2", String.class));
    }

    @Test
    void lateCancelRecoveryCannotOverwriteNewerExchangeOrderFact() throws Exception {
        var db = db();
        db.jdbc.update("INSERT INTO quant_execution_record(id,operation_type,execution_status) VALUES (1,'CANCEL','UNKNOWN')");
        var now = LocalDateTime.now();
        assertEquals(1, db.mapper.markRecoveredAfterFact(1L, 99L, "PARTIALLY_FILLED", now));
        assertEquals(0, db.mapper.annotateRecoveredCancelFill(1L, 98L, "PARTIALLY_FILLED", "stale event", now));
        assertEquals(0, db.mapper.annotateRecoveredCancelFill(1L, 99L, "FILLED", "stale status", now));
        assertEquals(1, db.mapper.annotateRecoveredCancelFill(1L, 99L, "PARTIALLY_FILLED", "cancel raced with fill", now));
        assertEquals("cancel raced with fill", db.jdbc.queryForObject(
            "SELECT error_message FROM quant_execution_record WHERE id=1", String.class));
        assertEquals("RECOVERED", db.jdbc.queryForObject(
            "SELECT execution_status FROM quant_execution_record WHERE id=1", String.class));
        assertEquals(99L, db.jdbc.queryForObject(
            "SELECT exchange_order_id FROM quant_execution_record WHERE id=1", Long.class));
    }

    @Test
    void conflictingExchangeOrderIdCannotReplacePersistedOrderIdentity() throws Exception {
        var db = db();
        var now = LocalDateTime.now();
        db.jdbc.update("INSERT INTO quant_execution_record(id,execution_status,exchange_order_id) VALUES (1,'UNKNOWN',101),(2,'CONFIRMED',201)");
        assertEquals(0, db.mapper.markAccepted(1L, 102L, "NEW", now));
        assertEquals(0, db.mapper.markRecoveredAfterFact(1L, 102L, "FILLED", now));
        assertEquals(0, db.mapper.markRecoveredAfterFact(2L, 202L, "FILLED", now));
        assertEquals(0, db.mapper.markRecoveredAfterFact(2L, null, "FILLED", now));
        assertEquals(101L, db.jdbc.queryForObject("SELECT exchange_order_id FROM quant_execution_record WHERE id=1", Long.class));
        assertEquals(201L, db.jdbc.queryForObject("SELECT exchange_order_id FROM quant_execution_record WHERE id=2", Long.class));
        assertEquals(1, db.mapper.markRecoveredAfterFact(2L, 201L, "FILLED", now));
    }

    @Test
    void recoveryCounterIsAtomicAndDoesNotChangeTerminalStatus() throws Exception {
        var db = db();
        db.jdbc.update("INSERT INTO quant_execution_record(id, execution_status) VALUES (1,'UNKNOWN')");
        var now = LocalDateTime.now();
        assertEquals(1, db.mapper.recordRecoveryAttempt(1L, now));
        assertEquals(1, db.mapper.recordRecoveryAttempt(1L, now));
        assertEquals(2, db.jdbc.queryForObject(
            "SELECT recovery_attempts FROM quant_execution_record WHERE id=1", Integer.class));
        assertEquals(1, db.mapper.markRecoveredAfterFact(1L, 12L, "FILLED", now));
        assertEquals(0, db.mapper.recordRecoveryAttempt(1L, now));
    }
}
