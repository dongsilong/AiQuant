package org.dromara.common.mybatis.interceptor;

import org.dromara.common.mybatis.config.properties.SqlLogProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Regression guard: successful statements must not be written to the console by default. */
class SqlErrorOnlyConfigTest {
    @Test
    void defaultConfigurationLogsOnlyFailuresWithoutParameterValues() {
        SqlLogProperties config = new SqlLogProperties();
        assertFalse(config.getEnabled());
        assertFalse(config.getLogSuccess());
        assertTrue(config.getLogError());
        assertFalse(config.getIncludeSql());
    }
}
