package org.dromara.quant.runtime.coordination;

import org.dromara.quant.config.QuantProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLTimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("dev")
class StrategyRuntimeLeaseServiceTest {
    @Test
    void leaseStatementsHaveTimeoutWithoutChangingSharedTemplate() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);
        JdbcTemplate shared = new JdbcTemplate(source);
        StrategyRuntimeLeaseService service = new StrategyRuntimeLeaseService(shared, new QuantProperties());
        assertTrue(service.acquireOrRenew(1L, 2L, "BTCUSDT"));
        verify(statement, times(2)).setQueryTimeout(3);
        assertEquals(-1, shared.getQueryTimeout());
    }

    @Test
    void timedOutRenewalDoesNotClaimOwnership() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(new SQLTimeoutException("test timeout"));
        StrategyRuntimeLeaseService service = new StrategyRuntimeLeaseService(new JdbcTemplate(source), new QuantProperties());
        assertFalse(service.acquireOrRenew(1L, 2L, "BTCUSDT"));
    }
}
