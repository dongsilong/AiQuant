package org.dromara.quant.reconcile.mapper;

import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class StrategyFillSyncCursorMapperTest {
    @Test
    void cursorAdvancesMonotonicallyAndListsKnownSymbols() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:fill_cursor_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        new JdbcTemplate(source).execute("""
            CREATE TABLE quant_strategy_fill_sync_cursor (
              credential_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
              last_complete_to DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL,
              PRIMARY KEY(credential_id,symbol))
            """);
        var configuration = new Configuration();
        configuration.addMapper(StrategyFillSyncCursorMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(StrategyFillSyncCursorMapper.class);
        var first = LocalDateTime.of(2026, 10, 17, 0, 0);
        var later = first.plusHours(1);

        assertEquals(1, mapper.insertCursor(9L, "BTCUSDT", first));
        assertEquals(1, mapper.advanceIfBehind(9L, "BTCUSDT", later));
        assertEquals(0, mapper.advanceIfBehind(9L, "BTCUSDT", first.minusHours(1)));

        assertEquals(later, mapper.selectLastCompleteTo(9L, "BTCUSDT"));
        assertEquals(java.util.List.of("BTCUSDT"), mapper.selectSymbols(9L));
    }
}
