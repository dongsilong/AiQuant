package org.dromara.quant.risk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.dromara.quant.finance.mapper.QuantFinancialEventMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.AccountPositionCache;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.risk.domain.QuantRiskConfig;
import org.dromara.quant.risk.mapper.QuantRiskConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class RiskDecisionServiceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"100,true", "0,true", "-999,true", "-1000,false", "-1200,false"})
    void dailyLossBlocksOnlyAtOrBeyondLimit(String realized, boolean approved) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), org.dromara.quant.finance.domain.QuantFinancialEvent.class);
        var risks = mock(QuantRiskConfigMapper.class);
        var finance = mock(QuantFinancialEventMapper.class);
        var config = new QuantRiskConfig();
        config.setWhitelist(true);
        config.setDailyLossLimit(new BigDecimal("1000"));
        when(risks.selectOne(any())).thenReturn(config);
        var event = new org.dromara.quant.finance.domain.QuantFinancialEvent();
        event.setAmount(new BigDecimal(realized));
        when(finance.selectList(any())).thenReturn(java.util.List.of(event));
        var service = new RiskDecisionService(risks, mock(QuantPositionMapper.class), mock(QuantExecutionRecordMapper.class), mock(QuantTradeMapper.class), finance);
        assertEquals(approved, service.approveOpen(1L, "TACUSDT", BigDecimal.TEN, "TESTNET"));
        if (approved) {
            assertDoesNotThrow(() -> service.requireOpenApproved(1L, "TACUSDT", BigDecimal.TEN, "TESTNET"));
        } else {
            assertThrows(ServiceException.class, () -> service.requireOpenApproved(1L, "TACUSDT", BigDecimal.TEN, "TESTNET"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CONFIG", "WHITELIST", "LIVE", "NOTIONAL", "POSITIONS", "FREQUENCY", "LOSS"})
    void refusalIdentifiesTheFailedRule(String rule) {
        var risks = mock(QuantRiskConfigMapper.class);
        var positions = mock(QuantPositionMapper.class);
        var executions = mock(QuantExecutionRecordMapper.class);
        var finance = mock(QuantFinancialEventMapper.class);
        var config = new QuantRiskConfig();
        config.setWhitelist(true);
        config.setLiveEnabled(true);
        var freshness = new org.dromara.quant.reconcile.LivePositionFreshness();
        freshness.verified(1L);
        var positionCache = mock(AccountPositionCache.class);
        when(positionCache.requirePositions(1L)).thenReturn(java.util.List.of());
        String expected;
        switch (rule) {
            case "CONFIG" -> expected = "未配置账户风控";
            case "WHITELIST" -> {
                config.setWhitelist(false);
                expected = "账户未启用交易白名单";
            }
            case "LIVE" -> {
                config.setLiveEnabled(false);
                expected = "账户未启用实盘交易";
            }
            case "NOTIONAL" -> {
                config.setMaxOrderNotional(BigDecimal.ONE);
                expected = "单笔订单名义金额超限";
            }
            case "POSITIONS" -> {
                config.setMaxOpenPositions(2);
                when(positionCache.requirePositions(1L)).thenReturn(java.util.List.of(
                    new ExchangeGateway.PositionSnapshot("BRUSDT", "LONG", BigDecimal.ONE, null, null, null, null, null, null, null, null),
                    new ExchangeGateway.PositionSnapshot("BTCUSDT", "SHORT", BigDecimal.ONE.negate(), null, null, null, null, null, null, null, null)));
                expected = "账户持仓数量达到上限";
            }
            case "FREQUENCY" -> {
                config.setMaxOrdersPerMinute(3);
                when(executions.selectCount(any())).thenReturn(3L);
                expected = "每分钟开仓次数达到上限";
            }
            default -> {
                config.setDailyLossLimit(new BigDecimal("20"));
                var event = new org.dromara.quant.finance.domain.QuantFinancialEvent();
                event.setAmount(new BigDecimal("-20"));
                when(finance.selectList(any())).thenReturn(java.util.List.of(event));
                expected = "当日亏损达到上限";
            }
        }
        when(risks.selectOne(any())).thenReturn(rule.equals("CONFIG") ? null : config);
        var service = new RiskDecisionService(risks, positions, executions, mock(QuantTradeMapper.class), finance);
        ReflectionTestUtils.setField(service, "positionFreshness", freshness);
        ReflectionTestUtils.setField(service, "positionCache", positionCache);
        var error = assertThrows(ServiceException.class, () -> service.requireOpenApproved(1L, "BRUSDT", BigDecimal.TEN, "LIVE", "current"));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
        assertTrue(error.getMessage().contains("BRUSDT"));
        assertFalse(service.approveOpen(1L, "BRUSDT", BigDecimal.TEN, "LIVE"));
    }

    @Test
    void currentPendingRequestIsExcludedButPreviousOrderStillConsumesFrequencyLimit() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), QuantExecutionRecord.class);
        var mapper = mock(QuantRiskConfigMapper.class);
        var executions = mock(QuantExecutionRecordMapper.class);
        var config = new QuantRiskConfig();
        config.setWhitelist(true);
        config.setMaxOrdersPerMinute(1);
        when(mapper.selectOne(any())).thenReturn(config);
        when(executions.selectCount(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<QuantExecutionRecord> query = invocation.getArgument(0);
            String sql = query.getSqlSegment().replace("_", "").replace("`", "").toLowerCase(java.util.Locale.ROOT);
            assertTrue(sql.contains("idempotencykey <>") || sql.contains("idempotencykey !="), sql);
            assertTrue(query.getParamNameValuePairs().containsValue("current"));
            return 0L;
        });
        var service = new RiskDecisionService(mapper, mock(QuantPositionMapper.class), executions, mock(QuantTradeMapper.class), mock(QuantFinancialEventMapper.class));
        assertDoesNotThrow(() -> service.requireOpenApproved(1L, "BTCUSDT", BigDecimal.TEN, "TESTNET", "current"));
        doReturn(1L).when(executions).selectCount(any());
        assertThrows(ServiceException.class, () -> service.requireOpenApproved(1L, "BTCUSDT", BigDecimal.TEN, "TESTNET", "current"));
    }
}
