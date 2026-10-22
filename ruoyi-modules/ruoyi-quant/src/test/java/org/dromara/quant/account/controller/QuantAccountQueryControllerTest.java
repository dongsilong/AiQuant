package org.dromara.quant.account.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.domain.vo.QuantTradeVo;
import org.dromara.quant.account.mapper.QuantAccountSnapshotMapper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class QuantAccountQueryControllerTest {
    @Test
    void queriesProtectionOrdersThroughTheAuthenticatedAccountScope() throws Exception {
        QuantCredentialMapper mapper = mock(QuantCredentialMapper.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        QuantCredential credential = new QuantCredential();
        credential.setUserId(1L);
        credential.setRunMode("TESTNET");
        when(mapper.selectById(10L)).thenReturn(credential);
        when(gateway.queryProtectionOrders(any(), eq("PUMPUSDT"))).thenReturn(List.of());
        var controller = new QuantAccountQueryController(mapper, gateway, mock(QuantTradeMapper.class), mock(QuantAccountSnapshotMapper.class));
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(() -> LoginHelper.isSuperAdmin(1L)).thenReturn(false);
            assertTrue(controller.protectionOrders(10L, "PUMPUSDT").getData().isEmpty());
            verify(gateway).queryProtectionOrders(argThat(value -> "TESTNET".equals(value.runMode())), eq("PUMPUSDT"));
        }
        var permission = QuantAccountQueryController.class.getMethod("protectionOrders", Long.class, String.class).getAnnotation(SaCheckPermission.class);
        assertArrayEquals(new String[]{"quant:order:list"}, permission.value());
    }

    @Test
    void rejectsAnotherUsersCredentialBeforeQueryingExchange() {
        QuantCredentialMapper mapper = mock(QuantCredentialMapper.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        QuantCredential credential = new QuantCredential();
        credential.setUserId(1L);
        when(mapper.selectById(10L)).thenReturn(credential);
        var controller = new QuantAccountQueryController(mapper, gateway, mock(QuantTradeMapper.class), mock(QuantAccountSnapshotMapper.class));
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            login.when(() -> LoginHelper.isSuperAdmin(2L)).thenReturn(false);
            assertThrows(ServiceException.class, () -> controller.protectionOrders(10L, null));
            verifyNoInteractions(gateway);
        }
    }

    @Test
    void listsAccessibleTradesAcrossAccountsAndKeepsCredentialOwner() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QuantCredential.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), QuantTrade.class);
        QuantCredentialMapper credentialMapper = mock(QuantCredentialMapper.class);
        QuantTradeMapper tradeMapper = mock(QuantTradeMapper.class);
        QuantCredential credential = new QuantCredential();
        credential.setId(10L);
        credential.setUserId(2L);
        credential.setAccountName("模拟账户");
        when(credentialMapper.selectList(any())).thenReturn(List.of(credential));

        QuantTrade trade = new QuantTrade();
        trade.setCredentialId(10L);
        Page<QuantTrade> tradePage = new Page<>(1, 10);
        tradePage.setRecords(List.of(trade));
        tradePage.setTotal(1);
        when(tradeMapper.selectPage(any(Page.class), any())).thenReturn(tradePage);

        var controller = new QuantAccountQueryController(credentialMapper, mock(ExchangeGateway.class), tradeMapper, mock(QuantAccountSnapshotMapper.class));
        PageQuery pageQuery = new PageQuery();
        pageQuery.setPageNum(1);
        pageQuery.setPageSize(10);
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            login.when(() -> LoginHelper.isSuperAdmin(2L)).thenReturn(false);

            var result = controller.tradePageForAccessibleAccounts(null, null, null, null, null, pageQuery).getData();

            assertEquals(1, result.getTotal());
            var row = (QuantTradeVo) result.getRows().iterator().next();
            assertEquals(10L, row.getCredentialId());
            assertEquals(2L, row.getUserId());
            assertEquals("模拟账户", row.getCustomerName());
            verify(credentialMapper).selectList(argThat(wrapper -> wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper
                && abstractWrapper.getSqlSegment().contains("user_id") && abstractWrapper.getParamNameValuePairs().containsValue(2L)));
        }
    }
}
