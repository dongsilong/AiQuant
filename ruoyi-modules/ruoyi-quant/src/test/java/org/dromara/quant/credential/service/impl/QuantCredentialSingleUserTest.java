package org.dromara.quant.credential.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class QuantCredentialSingleUserTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"LIVE", "TESTNET"})
    void credentialValidationKeepsTransferFlagWithoutRequiringIt(String mode) throws Exception {
        var constructor = QuantCredentialServiceImpl.class.getConstructors()[0];
        Object[] args = java.util.Arrays.stream(constructor.getParameterTypes()).map(org.mockito.Mockito::mock).toArray();
        var service = (QuantCredentialServiceImpl) constructor.newInstance(args);
        var gateway = (org.dromara.quant.exchange.ExchangeGateway) org.springframework.test.util.ReflectionTestUtils.getField(service, "exchangeGateway");
        var credential = new org.dromara.quant.credential.domain.QuantCredential();
        credential.setApiKey("test-key");
        credential.setSecretKey("test-secret");
        credential.setSignatureType("HMAC");
        credential.setRunMode(mode);
        org.mockito.Mockito.when(gateway.validateCredential(org.mockito.ArgumentMatchers.any())).thenReturn(
            new org.dromara.quant.exchange.ExchangeGateway.ConnectivityResult(true, "OK", true, false, java.time.Instant.now()));

        assertDoesNotThrow(() -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "validateCredential", credential));
        assertEquals("NORMAL", credential.getConnectionStatus());
        assertEquals(true, credential.getTradePermission());
        assertEquals(false, credential.getWithdrawalDisabled(), "must not fabricate a disabled-withdrawal assertion");

        org.mockito.Mockito.when(gateway.validateCredential(org.mockito.ArgumentMatchers.any())).thenReturn(
            new org.dromara.quant.exchange.ExchangeGateway.ConnectivityResult(false, "invalid credential", false, false, java.time.Instant.now()));
        assertThrows(ServiceException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "validateCredential", credential));
    }

    @Test
    void singleCredentialOwnerIsRequired() {
        assertDoesNotThrow(() -> QuantCredentialServiceImpl.validateSingleBoundUser(12L));
        assertThrows(ServiceException.class, () -> QuantCredentialServiceImpl.validateSingleBoundUser(null));
        assertThrows(ServiceException.class, () -> QuantCredentialServiceImpl.validateSingleBoundUser(0L));
    }

    @Test
    void credentialServiceMustNotQueryDeletedGrantsTable() throws Exception {
        var fields = QuantCredentialServiceImpl.class.getDeclaredFields();
        assertTrue(java.util.Arrays.stream(fields).noneMatch(f -> f.getType().getSimpleName().equals("QuantCredentialUserMapper")));
        assertThrows(NoSuchFieldException.class, () -> org.dromara.quant.credential.domain.bo.QuantCredentialBo.class.getDeclaredField("userIds"));
        assertThrows(NoSuchFieldException.class, () -> org.dromara.quant.credential.domain.vo.QuantCredentialVo.class.getDeclaredField("userIds"));
    }
}
