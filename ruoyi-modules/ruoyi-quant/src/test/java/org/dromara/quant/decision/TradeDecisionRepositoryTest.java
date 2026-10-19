package org.dromara.quant.decision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
class TradeDecisionRepositoryTest {
    JdbcTemplate jdbc;
    TradeDecisionLogRepository repository;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + java.util.UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE quant_trade_decision_log(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_key VARCHAR(64) NOT NULL UNIQUE,record_type VARCHAR(24) NOT NULL,parent_key VARCHAR(64)," +
            "credential_id BIGINT NOT NULL,account_name VARCHAR(255),run_mode VARCHAR(16),strategy_id BIGINT,strategy_instance_id BIGINT,strategy_name VARCHAR(255),symbol VARCHAR(40) NOT NULL," +
            "operation VARCHAR(16),direction VARCHAR(16),status VARCHAR(32),reason_code VARCHAR(64),reason VARCHAR(2000),angle DECIMAL(20,8),threshold DECIMAL(20,8),kline_time TIMESTAMP," +
            "quantity DECIMAL(38,16),price DECIMAL(38,16),margin DECIMAL(38,16),idempotency_key VARCHAR(255),order_id BIGINT,client_order_id VARCHAR(128),event_time TIMESTAMP NOT NULL," +
            "trade_time TIMESTAMP,repeat_count BIGINT NOT NULL,last_time TIMESTAMP NOT NULL)");
        jdbc.execute("CREATE TABLE quant_credential(id BIGINT,account_name VARCHAR(255),run_mode VARCHAR(20))");
        jdbc.execute("CREATE TABLE quant_strategy(id BIGINT,strategy_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE quant_execution_record(credential_id BIGINT,symbol VARCHAR(40),operation_type VARCHAR(20),exchange_order_id BIGINT,client_order_id VARCHAR" +
            "(128),created_at TIMESTAMP,strategy_id BIGINT,strategy_instance_id BIGINT,idempotency_key VARCHAR(255))");
        jdbc.execute("CREATE TABLE quant_order(credential_id BIGINT,symbol VARCHAR(40),exchange_order_id BIGINT,strategy_id BIGINT,strategy_instance_id BIGINT,reduce_only " +
            "BOOLEAN,position_side VARCHAR(20),status VARCHAR(30))");
        jdbc.execute("CREATE TABLE quant_protection_order(credential_id BIGINT,symbol VARCHAR(40),exchange_order_id BIGINT,client_order_id VARCHAR(128),protection_type VARCHAR" +
            "(30),position_id BIGINT,position_side VARCHAR(10),step_index INT,quantity DECIMAL(32,16),stop_price DECIMAL(32,16))");
        jdbc.execute("CREATE TABLE quant_strategy_position_state(position_id BIGINT,instance_id BIGINT,direction VARCHAR(10),entry_price DECIMAL(32,16),frozen_leverage INT," +
            "highest_price_since_entry DECIMAL(32,16),lowest_price_since_entry DECIMAL(32,16),strategy_params_snapshot VARCHAR(2000),frozen_stop_loss_ratio DECIMAL(16,8),frozen_symbol_stop_loss_ratio DECIMAL(16,8))");
        jdbc.execute("CREATE TABLE quant_strategy_instance(id BIGINT,strategy_id BIGINT)");
        jdbc.update("INSERT INTO quant_credential VALUES(1,'测试账户','TESTNET')");
        jdbc.update("INSERT INTO quant_strategy VALUES(2,'原策略名称')");
        repository = new TradeDecisionLogRepository(jdbc);
    }

    DecisionLogEvent request() {
        return DecisionLogEvent.builder().eventKey(TradeDecisionLogRepository.executionKey(1L, "open-1")).recordType("EXECUTION").credentialId(1L).accountName("账户快照").runMode(
            "TESTNET").strategyId(2L).strategyInstanceId(3L).strategyName("策略快照").symbol("BTCUSDT").operation("OPEN").direction("LONG").status("REQUESTED").reasonCode(
                "SLOPE_ENTRY").reason("角度达到开仓阈值").quantity(new BigDecimal("2")).price(new BigDecimal("100")).idempotencyKey("open-1").eventTime(Instant.now()).build();
    }

    @Test
    void snapshotSurvivesRenameAndAcceptedIsNotFilled() {
        var request = request();
        repository.writeBatch(List.of(request));
        jdbc.update("UPDATE quant_strategy SET strategy_name='新策略名称'");
        repository.writeBatch(List.of(request.toBuilder().strategyName(null).accountName(null).runMode(null).status("ACCEPTED").reasonCode("EXECUTION_RESULT").reason("已受理").eventTime(Instant.now()).build()));
        var rows = repository.list(new TradeDecisionQuery()).getRows();
        var row = rows.iterator().next();
        assertEquals("策略快照", row.get("strategyName"));
        assertEquals("账户快照", row.get("accountName"));
        assertEquals("ACCEPTED", row.get("status"));
        assertEquals("REFERENCE", row.get("priceKind"));
        assertEquals("角度达到开仓阈值", row.get("reason"));
        assertEquals("200", row.get("amount"));
    }

    @Test
    void sameFillViaRestAndWebsocketCountsOnceAndLateRequestCannotEraseFill() {
        var req = request();
        repository.writeBatch(List.of(req));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',88,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        var fill =
            DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(88L).tradeId(9L).direction("BUY").quantity(new BigDecimal("1")).price(new BigDecimal("101")).orderStatus("PARTIALLY_FILLED").tradeTime(Instant.now()).eventTime(Instant.now()).build();
        repository.writeBatch(List.of(fill, fill));
        repository.writeBatch(List.of(fill.toBuilder().tradeId(10L).price(new BigDecimal("103")).orderStatus("FILLED").build()));
        repository.writeBatch(List.of(req.toBuilder().eventTime(Instant.now()).build()));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals(0, new BigDecimal(row.get("quantity").toString()).compareTo(new BigDecimal("2")));
        assertEquals(0, new BigDecimal(row.get("price").toString()).compareTo(new BigDecimal("102")));
        assertEquals("204", row.get("amount"));
        assertEquals("FILLED", row.get("status"));
        assertEquals("ACTUAL", row.get("priceKind"));
        assertEquals(1, repository.list(new TradeDecisionQuery()).getTotal());
    }

    @Test
    void repeatedBlockedReasonIsMergedButChangedReasonIsSeparate() {
        var first = request().toBuilder().eventKey("E:first").recordType("EVALUATION").status("BLOCKED").reasonCode("MAX_POSITIONS").build();
        repository.writeBatch(List.of(first, first, first));
        repository.writeBatch(List.of(first));
        var second = first.toBuilder().eventKey("E:second").reasonCode("ACCOUNT_NOT_READY").build();
        repository.writeBatch(List.of(second));
        var rows = repository.list(new TradeDecisionQuery()).getRows();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(row -> "4".equals(row.get("repeatCount").toString())));
    }

    @Test
    void earlyFillCanBeReattributedToExecutionWithoutDuplicateOrLostQuantity() {
        var fill =
            DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(99L).tradeId(11L).direction("BUY").quantity(BigDecimal.ONE).price(BigDecimal.TEN).cumulativeQuantity(new BigDecimal("2")).averagePrice(new BigDecimal("12")).eventTime(Instant.now()).tradeTime(Instant.now()).build();
        repository.writeBatch(List.of(fill));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',99,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        repository.writeBatch(List.of(request().toBuilder().orderId(99L).build()));
        repository.writeBatch(List.of(fill));
        assertEquals(1, repository.list(new TradeDecisionQuery()).getTotal());
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("策略快照", row.get("strategyName"));
        assertEquals("24", row.get("amount"));
    }

    @Test
    void canceledPartialFillKeepsTerminalStatusAndAmountOnRestReplay() {
        repository.writeBatch(List.of(request()));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',88,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        var fill =
            DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(88L).tradeId(9L).direction("BUY").quantity(BigDecimal.ONE).price(BigDecimal.TEN).orderStatus("PARTIALLY_FILLED").cumulativeQuantity(BigDecimal.ONE).averagePrice(BigDecimal.TEN).tradeTime(Instant.now()).eventTime(Instant.now()).build();
        repository.writeBatch(List.of(fill));
        var canceled = fill.toBuilder().recordType("ORDER").quantity(null).price(null).tradeTime(null).tradeId(null).eventTime(Instant.now()).status("CANCELED").orderStatus(
            "CANCELED").build();
        repository.writeBatch(List.of(canceled));
        repository.writeBatch(List.of(fill));
        repository.writeBatch(List.of(fill.toBuilder().cumulativeQuantity(null).averagePrice(null).orderStatus(null).eventTime(Instant.now()).build()));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("CANCELED", row.get("status"));
        assertEquals("10", row.get("amount"));
        assertEquals("ACTUAL", row.get("priceKind"));
    }

    @Test
    void cumulativeFillPreservesActualAmountWhenIndividualTradesAreIncomplete() {
        repository.writeBatch(List.of(request()));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',88,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        var fill =
            DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(88L).tradeId(9L).direction("BUY").quantity(BigDecimal.ONE).price(BigDecimal.TEN).orderStatus("FILLED").cumulativeQuantity(new BigDecimal("2")).averagePrice(new BigDecimal("12")).tradeTime(Instant.now()).eventTime(Instant.now()).build();
        repository.writeBatch(List.of(fill));
        repository.writeBatch(List.of(fill.toBuilder().cumulativeQuantity(null).averagePrice(null).orderStatus(null).build()));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("24", row.get("amount"));
        assertEquals("FILLED", row.get("status"));
    }

    @Test
    void unknownSourceUpgradesWhenExactProtectionAssociationBecomesAvailable() {
        var fill =
            DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(99L).tradeId(1L).direction("SELL").quantity(BigDecimal.ONE).price(BigDecimal.TEN).eventTime(Instant.now()).tradeTime(Instant.now()).build();
        repository.writeBatch(List.of(fill));
        jdbc.update("INSERT INTO quant_protection_order(credential_id,symbol,exchange_order_id,client_order_id,protection_type,position_id,step_index,quantity,stop_price) VALUES(1,'BTCUSDT',99,'protect','STOP_LOSS',7,0,1,9)");
        jdbc.update("INSERT INTO quant_strategy_position_state(position_id,instance_id) VALUES(7,3)");
        jdbc.update("INSERT INTO quant_strategy_instance VALUES(3,2)");
        repository.writeBatch(List.of(fill));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("原策略名称", row.get("strategyName"));
        assertEquals("CLOSE", row.get("operation"));
        assertEquals("LONG", row.get("direction"));
        assertEquals("EXCHANGE_PROTECTION", row.get("reasonCode"));
    }

    @Test
    void exchangeStepTakeProfitExplainsPeakRetracementAndFilledQuantity() {
        jdbc.update("INSERT INTO quant_protection_order(credential_id,symbol,exchange_order_id,client_order_id,protection_type,position_id,step_index,quantity,stop_price) VALUES(1,'BTCUSDT',99,'step-tp','STEP_TP',7,3,2,106.6666667)");
        jdbc.update("INSERT INTO quant_strategy_position_state(position_id,instance_id,direction,entry_price,frozen_leverage,highest_price_since_entry,lowest_price_since_entry,strategy_params_snapshot,frozen_stop_loss_ratio) VALUES(7,3,'LONG',100,3,110,100,'{\"stepRatio\":0.1}',0.05)");
        jdbc.update("INSERT INTO quant_strategy_instance VALUES(3,2)");
        var fill = DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(99L).tradeId(1L).direction("SELL").quantity(new BigDecimal("1.5")).price(new BigDecimal("106.6")).eventTime(Instant.now()).tradeTime(Instant.now()).build();

        repository.writeBatch(List.of(fill));

        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        String reason = row.get("reason").toString();
        assertTrue(reason.contains("阶梯止盈"));
        assertTrue(reason.contains("最大回报率=30%"));
        assertTrue(reason.contains("第3阶"));
        assertTrue(reason.contains("锁定回报率=20%"));
        assertTrue(reason.contains("止盈数量=1.5"));
        assertEquals("159.9", row.get("amount"));
    }

    @Test
    void protectionConfirmationExplainsFirstTakeProfitThresholdAndEstimatedNotional() {
        jdbc.update("INSERT INTO quant_protection_order(credential_id,symbol,exchange_order_id,client_order_id,protection_type,position_id,position_side,step_index,quantity,stop_price) VALUES(1,'BTCUSDT',99,'first-tp','FIRST_TP',7,'LONG',0,2,110)");
        jdbc.update("INSERT INTO quant_strategy_position_state(position_id,instance_id,direction,entry_price,frozen_leverage,strategy_params_snapshot) VALUES(7,3,'LONG',100,5,'{\"firstProfitTakingRate\":0.1}')");
        jdbc.update("INSERT INTO quant_strategy_instance VALUES(3,2)");
        var event = DecisionLogEvent.builder().eventKey("O:1:BTCUSDT:99").recordType("PROTECTION").credentialId(1L).symbol("BTCUSDT").operation("CLOSE").direction("LONG")
            .status("ACCEPTED").reasonCode("EXCHANGE_PROTECTION").orderId(99L).clientOrderId("first-tp").eventTime(Instant.now()).build();

        repository.writeBatch(List.of(event));

        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        String reason = row.get("reason").toString();
        assertTrue(reason.contains("首次止盈率10%"));
        assertTrue(reason.contains("止盈数量=2"));
        assertTrue(reason.contains("约220 USDT（按触发价估算）"));
    }

    @Test
    void restOrderFillUpdatesOpenAndCloseStatusAndActualAmountWithoutPrivateTradeEvent() {
        var req = request();
        repository.writeBatch(List.of(req));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',88,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        var filled =
            DecisionLogEvent.builder().recordType("ORDER").credentialId(1L).symbol("BTCUSDT").orderId(88L).clientOrderId("client").status("FILLED").orderStatus("FILLED").direction("BUY").quantity(new BigDecimal("2")).price(new BigDecimal("101")).tradeTime(Instant.now()).eventTime(Instant.now()).build();
        repository.writeBatch(List.of(filled));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("FILLED", row.get("status"));
        assertEquals("202", row.get("amount"));
        assertEquals("ACTUAL", row.get("priceKind"));
        assertEquals("OPEN", row.get("operation"));
        var close = req.toBuilder().eventKey(TradeDecisionLogRepository.executionKey(1L, "close-1")).idempotencyKey("close-1").operation("CLOSE").build();
        repository.writeBatch(List.of(close));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','CLOSE',89,'close-client',CURRENT_TIMESTAMP,2,3,'close-1')");
        repository.writeBatch(List.of(filled.toBuilder().orderId(89L).clientOrderId("close-client").direction("SELL").build()));
        assertTrue(repository.list(new TradeDecisionQuery()).getRows().stream().anyMatch(r -> "CLOSE".equals(r.get("operation")) && "FILLED".equals(r.get("status")) && "202".equals(r.get("amount"))));
    }

    @Test
    void confirmedFillWithoutAveragePriceMustNotRelabelReferencePriceAsActual() {
        repository.writeBatch(List.of(request()));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',88,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        var filled =
            DecisionLogEvent.builder().recordType("ORDER").credentialId(1L).symbol("BTCUSDT").orderId(88L).status("FILLED").orderStatus("FILLED").quantity(BigDecimal.ONE).tradeTime(Instant.now()).eventTime(Instant.now()).build();
        repository.writeBatch(List.of(filled));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("FILLED", row.get("status"));
        assertNull(row.get("price"));
        assertNull(row.get("amount"));
    }

    @Test
    void growingCumulativeQuantityWithoutAverageClearsOldPrice() {
        repository.writeBatch(List.of(request()));
        jdbc.update("INSERT INTO quant_execution_record VALUES(1,'BTCUSDT','OPEN',88,'client',CURRENT_TIMESTAMP,2,3,'open-1')");
        var partial =
            DecisionLogEvent.builder().recordType("ORDER").credentialId(1L).symbol("BTCUSDT").orderId(88L).status("PARTIALLY_FILLED").quantity(BigDecimal.ONE).price(new BigDecimal("100")).tradeTime(Instant.now()).eventTime(Instant.now()).build();
        repository.writeBatch(List.of(partial));
        repository.writeBatch(List.of(partial.toBuilder().price(null).eventTime(Instant.now()).build()));
        assertEquals("100", repository.list(new TradeDecisionQuery()).getRows().iterator().next().get("amount"));
        repository.writeBatch(List.of(partial.toBuilder().quantity(new BigDecimal("2")).price(null).status("FILLED").eventTime(Instant.now()).build()));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("FILLED", row.get("status"));
        assertNull(row.get("price"));
        assertNull(row.get("amount"));
    }

    @Test
    void externalUnknownDoesNotGuessDirectionOrStrategy() {
        var fill =
            DecisionLogEvent.builder().recordType("FILL").credentialId(1L).symbol("BTCUSDT").orderId(99L).tradeId(1L).direction("SELL").quantity(BigDecimal.ONE).price(BigDecimal.TEN).eventTime(Instant.now()).tradeTime(Instant.now()).build();
        repository.writeBatch(List.of(fill));
        var row = repository.list(new TradeDecisionQuery()).getRows().iterator().next();
        assertEquals("外部交易／策略未确认", row.get("strategyName"));
        assertEquals("UNKNOWN", row.get("direction"));
    }
}
