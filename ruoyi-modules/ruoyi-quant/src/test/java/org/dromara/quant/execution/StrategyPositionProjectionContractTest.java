package org.dromara.quant.execution;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class StrategyPositionProjectionContractTest {
    @Test
    void inventoryUsesRowLockAndReplayIsUnique() throws Exception {
        var lock = StrategyPositionProjectionMapper.class.getMethod(
            "lock", long.class, String.class, long.class, String.class);
        String sql = String.join(" ", lock.getAnnotation(Select.class).value());
        assertTrue(sql.contains("FOR UPDATE"));
        var existing = StrategyPositionProjectionMapper.class.getMethod(
            "existing", long.class, String.class, long.class);
        assertTrue(String.join(" ", existing.getAnnotation(Select.class).value())
            .contains("exchange_trade_id=#{tradeId}"));
        var insert = StrategyPositionProjectionMapper.class.getMethod("insertFill",
            long.class,long.class,String.class,long.class,long.class,long.class,
            long.class,long.class,String.class,String.class,String.class,
            java.math.BigDecimal.class,java.math.BigDecimal.class,
            java.math.BigDecimal.class,java.math.BigDecimal.class,
            java.time.LocalDateTime.class);
        assertTrue(String.join(" ",insert.getAnnotation(Insert.class).value())
            .contains("quant_strategy_position_fill_event"));
    }
}
