package org.dromara.quant.credential.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class QuantCredentialSingleUserTest {
    @Test
    void singleCredentialOwnerIsRequired() {
        assertDoesNotThrow(() -> QuantCredentialServiceImpl.validateSingleBoundUser(12L));
        assertThrows(ServiceException.class,
            () -> QuantCredentialServiceImpl.validateSingleBoundUser(null));
        assertThrows(ServiceException.class,
            () -> QuantCredentialServiceImpl.validateSingleBoundUser(0L));
    }

    @Test
    void credentialServiceMustNotQueryDeletedGrantsTable() throws Exception {
        var fields = QuantCredentialServiceImpl.class.getDeclaredFields();
        assertTrue(java.util.Arrays.stream(fields).noneMatch(f ->
            f.getType().getSimpleName().equals("QuantCredentialUserMapper")));
        assertThrows(NoSuchFieldException.class,
            () -> org.dromara.quant.credential.domain.bo.QuantCredentialBo.class
                .getDeclaredField("userIds"));
        assertThrows(NoSuchFieldException.class,
            () -> org.dromara.quant.credential.domain.vo.QuantCredentialVo.class
                .getDeclaredField("userIds"));
    }
}
