package org.dromara.quant.controller;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.QuantAdminController;
import org.dromara.config.domain.QuantCredential;
import org.dromara.config.mapper.QuantCredentialMapper;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class ExecutionLedgerQueryTest {
    @Test
    void defaultsTo24HoursAndSupportsSymbolOrderStatusAndRange() {
        var controller = mock(QuantAdminController.class, CALLS_REAL_METHODS);
        var credentials = mock(QuantCredentialMapper.class);
        var executions = mock(QuantExecutionRecordMapper.class);
        ReflectionTestUtils.setField(controller, "credentialMapper", credentials);
        ReflectionTestUtils.setField(controller, "executionMapper", executions);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), QuantExecutionRecord.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), QuantCredential.class);
        var account = new QuantCredential(); account.setId(1L); account.setAccountName("test");
        when(credentials.selectList(any(Wrapper.class))).thenReturn(List.of(account));
        var record = new QuantExecutionRecord(); record.setCredentialId(1L); record.setSymbol("BTCUSDT"); record.setExchangeOrderStatus("FILLED");
        var page = new Page<QuantExecutionRecord>(); page.setRecords(List.of(record)); page.setTotal(1);
        when(executions.selectPage(any(), any())).thenReturn(page);
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(() -> LoginHelper.isSuperAdmin(1L)).thenReturn(true);
            var before = LocalDateTime.now().minusHours(24);
            var result = controller.execution(new PageQuery(), 1L, null, null, null, null, null);
            var filters = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
            verify(executions).selectPage(any(), filters.capture());
            filters.getValue().getSqlSegment();
            var params = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) filters.getValue()).getParamNameValuePairs().values();
            assertTrue(params.stream().anyMatch(value -> value instanceof LocalDateTime time && !time.isBefore(before) && time.isBefore(before.plusSeconds(5))));
            assertEquals("BTCUSDT", result.getData().getRows().iterator().next().get("symbol"));
            assertEquals("FILLED", result.getData().getRows().iterator().next().get("exchangeOrderStatus"));
            clearInvocations(executions);
            var from = LocalDateTime.of(2026, 10, 11, 12, 0);
            var to = from.plusHours(1);
            controller.execution(new PageQuery(), 1L, "RECOVERED", "btcusdt", "filled", from, to);
            verify(executions).selectPage(any(), filters.capture());
            filters.getValue().getSqlSegment();
            params = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) filters.getValue()).getParamNameValuePairs().values();
            assertTrue(params.containsAll(List.of("RECOVERED", "BTCUSDT", "FILLED", from, to)));
            assertThrows(org.dromara.common.core.exception.ServiceException.class,
                () -> controller.execution(new PageQuery(), 1L, null, null, null, to, from));
            assertThrows(org.dromara.common.core.exception.ServiceException.class,
                () -> controller.execution(new PageQuery(), 2L, null, null, null, from, to));
        }
    }
}
