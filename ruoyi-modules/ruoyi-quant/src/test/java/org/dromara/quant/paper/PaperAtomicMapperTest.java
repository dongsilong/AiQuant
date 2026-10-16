package org.dromara.quant.paper;

import org.apache.ibatis.session.Configuration;
import org.dromara.quant.paper.mapper.PaperAtomicMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("dev")
class PaperAtomicMapperTest {
    @Test
    void balanceReservationIsAtomicAndFailsClosedWhenFundsRunOut() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:paper_mapper_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
            CREATE TABLE quant_paper_account (
              id BIGINT PRIMARY KEY,
              credential_id BIGINT UNIQUE NOT NULL,
              balance DECIMAL(38,16),
              available_balance DECIMAL(38,16),
              realized_pnl DECIMAL(38,16),
              unrealized_pnl DECIMAL(38,16),
              updated_at TIMESTAMP)
            """);
        jdbc.update("""
            INSERT INTO quant_paper_account
              (id,credential_id,balance,available_balance,realized_pnl,unrealized_pnl)
            VALUES (1,5,100,100,0,0)
            """);
        var config = new Configuration();
        config.addMapper(PaperAtomicMapper.class);
        var bean = new SqlSessionFactoryBean();
        bean.setDataSource(dataSource);
        bean.setConfiguration(config);
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(bean.getObject())).getMapper(PaperAtomicMapper.class);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        transactions.executeWithoutResult(status -> {
            var account = mapper.lockAccount(5L);
            assertNotNull(account);
            assertEquals(0, new BigDecimal("100").compareTo(account.getAvailableBalance()));
            assertEquals(1, mapper.reserveMargin(account.getId(), new BigDecimal("90")));
        });
        transactions.executeWithoutResult(status -> {
            var account = mapper.lockAccount(5L);
            assertEquals(0, new BigDecimal("10").compareTo(account.getAvailableBalance()));
            assertEquals(0, mapper.reserveMargin(account.getId(), new BigDecimal("20")));
            assertEquals(1, mapper.refundMargin(account.getId(), new BigDecimal("2")));
        });
        assertEquals(0, new BigDecimal("12").compareTo(jdbc.queryForObject("SELECT available_balance FROM quant_paper_account WHERE id=1", BigDecimal.class)));
    }
}
