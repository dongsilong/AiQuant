package org.dromara.quant.runtime;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.junit.jupiter.api.Tag("dev")
class PendingStateSqlTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"OPEN", "CLOSED"})
    void clearedPendingFieldsMustBeIncludedInActualUpdateSql(String status) {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        var info = TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "pending-test"), QuantStrategyPositionState.class);
        var state = new QuantStrategyPositionState();
        state.setId(1L);
        state.setStatus(status);
        var source = new XMLLanguageDriver().createSqlSource(configuration, "<script>UPDATE " + info.getTableName() + " <set>" + info.getAllSqlSet(false, "et.") + "</set> WHERE " +
            "id=#{et.id}</script>", Map.class);
        var bound = source.getBoundSql(Map.of("et", state));
        for (String field : new String[]{"pending_action", "pending_idempotency_key", "pending_client_order_id", "pending_order_id", "pending_order_time"}) {
            assertTrue(bound.getSql().contains(field + "="), bound.getSql());
        }
        assertFalse(bound.getSql().contains("entry_atr="), "Do not change null handling for unrelated fields");
    }
}
