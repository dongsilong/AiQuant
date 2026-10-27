package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.apache.ibatis.session.Configuration;
import org.dromara.quant.execution.mapper.StrategyFillAllocationMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.web.bind.annotation.GetMapping;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class StrategyFillAuditServiceTest {
    @Test
    void auditedTotalsUseExactVerifiedFillsAndNeverClaimLivePositionSize() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:fill_audit_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute("""
            CREATE TABLE quant_strategy_fill_allocation(
              id BIGINT PRIMARY KEY, credential_id BIGINT NOT NULL,
              strategy_id BIGINT NOT NULL, strategy_instance_id BIGINT NOT NULL,
              symbol VARCHAR(32), side VARCHAR(8), allocated_qty DECIMAL(38,16),
              allocated_price DECIMAL(38,16), allocated_fee DECIMAL(38,16),
              allocated_fee_asset VARCHAR(20),
              realized_pnl DECIMAL(38,16))
            """);
        jdbc.update("""
            INSERT INTO quant_strategy_fill_allocation VALUES
              (1,9007199254740993,10,20,'BTCUSDT','BUY',0.125,60000.12,0.50,'USDT',0.00),
              (2,9007199254740993,10,20,'BTCUSDT','SELL',0.050,61000.50,0.20,'USDT',47.50),
              (3,9007199254740993,10,21,'BTCUSDT','BUY',100,60000.12,50,'USDT',0),
              (4,9007199254740993,10,20,'ETHUSDT','BUY',5,100,1,'USDT',0)
            """);
        var conf = new Configuration();
        conf.addMapper(StrategyFillAllocationMapper.class);
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(conf);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/quant/StrategyFillAllocationMapper.xml"));
        var mapper = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject())).getMapper(StrategyFillAllocationMapper.class);
        var audit = new StrategyFillAuditService(mapper);
        var totals = audit.summarize(9007199254740993L, 10L, 20L, "btcusdt");
        assertEquals("9007199254740993", totals.credentialId());
        assertEquals("2", totals.fillCount());
        assertEquals("USDT", totals.feeAsset());
        assertEquals(0, new BigDecimal("0.125").compareTo(new BigDecimal(totals.boughtQty())));
        assertEquals(0, new BigDecimal("0.050").compareTo(new BigDecimal(totals.soldQty())));
        assertEquals(0, new BigDecimal("0.70").compareTo(new BigDecimal(totals.totalFees())));
        assertEquals(1, totals.feeBreakdown().size());
        assertEquals("USDT", totals.feeBreakdown().getFirst().asset());
        assertEquals(0, new BigDecimal("0.70").compareTo(new BigDecimal(totals.feeBreakdown().getFirst().amount())));
        assertEquals(0, new BigDecimal("47.50").compareTo(new BigDecimal(totals.realizedPnl())));
        assertEquals("LIFETIME_VERIFIED_FILLS_NOT_OPEN_POSITION", totals.allocationScope());
        jdbc.update("UPDATE quant_strategy_fill_allocation SET allocated_fee_asset='BNB' WHERE id=2");
        var mixedFees = audit.summarize(9007199254740993L, 10L, 20L, "BTCUSDT");
        assertNull(mixedFees.totalFees());
        assertNull(mixedFees.feeAsset());
        assertEquals(2, mixedFees.feeBreakdown().size());
        assertEquals("BNB", mixedFees.feeBreakdown().getFirst().asset());
        assertEquals(0, new BigDecimal("0.20").compareTo(new BigDecimal(mixedFees.feeBreakdown().getFirst().amount())));
        assertEquals("USDT", mixedFees.feeBreakdown().get(1).asset());
        jdbc.update("UPDATE quant_strategy_fill_allocation SET allocated_fee_asset='' WHERE id=2");
        var unknownFee = audit.summarize(9007199254740993L, 10L, 20L, "BTCUSDT");
        assertEquals("UNKNOWN", unknownFee.feeBreakdown().getFirst().asset());
        assertNull(unknownFee.totalFees());
        assertThrows(IllegalArgumentException.class, () -> audit.summarize(1L, 10L, 20L, "BTCUSDT;DROP TABLE"));
    }

    @Test
    void endpointRequiresAllocationViewPermission() throws Exception {
        var method = StrategyFillAuditController.class.getMethod("summary", String.class, String.class, String.class, String.class);
        assertArrayEquals(new String[]{"/summary"}, method.getAnnotation(GetMapping.class).value());
        var perm = method.getAnnotation(SaCheckPermission.class);
        assertNotNull(perm);
        assertEquals("quant:allocation:view", perm.value()[0]);
    }
}
