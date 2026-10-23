package org.dromara.quant.runtime.coordination.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("dev")
class StrategyRuntimeLedgerMapperTest {
    @Test
    void confirmedOpenExposureMustStillHaveAnExchangePositionProjection() throws Exception {
        Select select = StrategyRuntimeLedgerMapper.class.getMethod("countStrategyExposures", Long.class, Long.class).getAnnotation(Select.class);
        String sql = String.join(" ", select.value()).replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);

        assertTrue(sql.contains("left join quant_position p on p.id=s.position_id"), sql);
        assertTrue(sql.contains("(s.status <> 'open' or p.quantity > 0 or p.quantity is null)"), sql);
    }
}
