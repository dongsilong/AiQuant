package org.dromara.quant.execution;

import cn.dev33.satoken.annotation.SaCheckPermission;
import org.apache.ibatis.annotations.Select;
import org.dromara.quant.execution.mapper.LiveRecoveryAuditMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class LiveRecoveryAuditSecurityTest {
    @Test
    void auditEndpointNeedsDedicatedPermissionAndDoesNotWrite() throws Exception {
        Method endpoint = LiveManualRecoveryController.class.getMethod("audit", String.class);
        assertEquals("quant:dispatch:recovery:audit",
            endpoint.getAnnotation(SaCheckPermission.class).value()[0]);
        assertNotNull(endpoint.getAnnotation(GetMapping.class));
        assertTrue(Arrays.stream(LiveRecoveryAuditMapper.class.getDeclaredMethods())
            .noneMatch(m -> m.getName().matches("(?i).*(update|delete|truncate).*")));
        Method query = LiveRecoveryAuditMapper.class.getMethod("listByCase", long.class);
        String sql = String.join(" ", query.getAnnotation(Select.class).value());
        assertTrue(sql.contains("WHERE case_id=#{caseId}"));
        assertTrue(sql.contains("ORDER BY created_at ASC, audit_id ASC"));
        assertTrue(sql.contains("LIMIT 100"));
    }
}
