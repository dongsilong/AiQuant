package org.dromara.quant.account.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.account.mapper.QuantTradeMapper;
import org.dromara.config.domain.QuantCredential;
import org.dromara.config.mapper.QuantCredentialMapper;
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
        var controller = new QuantAccountQueryController(mapper, gateway, mock(QuantTradeMapper.class));
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(() -> LoginHelper.isSuperAdmin(1L)).thenReturn(false);
            assertTrue(controller.protectionOrders(10L, "PUMPUSDT").getData().isEmpty());
            verify(gateway).queryProtectionOrders(argThat(value -> "TESTNET".equals(value.runMode())), eq("PUMPUSDT"));
        }
        var permission = QuantAccountQueryController.class.getMethod("protectionOrders", Long.class, String.class).getAnnotation(SaCheckPermission.class);
        assertArrayEquals(new String[] {"quant:order:list"}, permission.value());
    }

    @Test
    void rejectsAnotherUsersCredentialBeforeQueryingExchange() {
        QuantCredentialMapper mapper = mock(QuantCredentialMapper.class);
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        QuantCredential credential = new QuantCredential();
        credential.setUserId(1L);
        when(mapper.selectById(10L)).thenReturn(credential);
        var controller = new QuantAccountQueryController(mapper, gateway, mock(QuantTradeMapper.class));
        try (var login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            login.when(() -> LoginHelper.isSuperAdmin(2L)).thenReturn(false);
            assertThrows(ServiceException.class, () -> controller.protectionOrders(10L, null));
            verifyNoInteractions(gateway);
        }
    }
}
