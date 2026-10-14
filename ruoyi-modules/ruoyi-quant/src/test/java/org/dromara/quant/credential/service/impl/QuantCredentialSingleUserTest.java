package org.dromara.quant.credential.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class QuantCredentialSingleUserTest {
    @Test
    void onePrincipalMayBeBoundAndLegacyUnspecifiedListMapsToOwner() {
        assertDoesNotThrow(() -> QuantCredentialServiceImpl.validateSingleBoundUser(List.of(12L),12L));
        assertDoesNotThrow(() -> QuantCredentialServiceImpl.validateSingleBoundUser(null,12L));
    }
    @Test
    void rejectsMultipleUsersIncludingDuplicatedSameId() {
        assertThrows(ServiceException.class, () ->
            QuantCredentialServiceImpl.validateSingleBoundUser(List.of(12L,13L),12L));
        assertThrows(ServiceException.class, () ->
            QuantCredentialServiceImpl.validateSingleBoundUser(List.of(12L,12L),12L));
    }
    @Test
    void rejectsDifferentOwnerAndUnassigningAllUsers() {
        assertThrows(ServiceException.class, () ->
            QuantCredentialServiceImpl.validateSingleBoundUser(List.of(13L),12L));
        assertThrows(ServiceException.class, () ->
            QuantCredentialServiceImpl.validateSingleBoundUser(List.of(),12L));
        assertThrows(ServiceException.class, () ->
            QuantCredentialServiceImpl.validateSingleBoundUser(null,null));
    }
}
