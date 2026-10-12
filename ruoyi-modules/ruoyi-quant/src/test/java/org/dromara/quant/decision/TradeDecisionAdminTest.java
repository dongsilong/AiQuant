package org.dromara.quant.decision;

import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Tag("dev")
class TradeDecisionAdminTest {
    @Test
    void ordinaryUserCannotQueryEvenWithMenuPermission() {
        var repository=mock(TradeDecisionLogRepository.class);
        var controller=new TradeDecisionLogController(repository);
        try(var login=mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(42L);
            login.when(() -> LoginHelper.isSuperAdmin(42L)).thenReturn(false);
            assertThrows(ServiceException.class,() -> controller.list(new TradeDecisionQuery()));
            assertThrows(ServiceException.class,() -> controller.detail(1L));
            verifyNoInteractions(repository);
        }
    }
    @Test
    void administratorCanQuery() {
        var repository=mock(TradeDecisionLogRepository.class);
        try(var login=mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(1L);
            login.when(() -> LoginHelper.isSuperAdmin(1L)).thenReturn(true);
            var query=new TradeDecisionQuery();
            new TradeDecisionLogController(repository).list(query);
            verify(repository).list(query);
        }
    }
}
