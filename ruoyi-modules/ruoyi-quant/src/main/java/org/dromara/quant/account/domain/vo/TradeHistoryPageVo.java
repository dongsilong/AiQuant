package org.dromara.quant.account.domain.vo;

import lombok.Getter;
import org.dromara.common.core.domain.PageResult;

import java.io.Serial;
import java.util.List;
import java.util.Map;

/**
 * 开平仓分页结果，保留前端 rows、total、incompleteCount 响应契约。
 */
@Getter
public class TradeHistoryPageVo extends PageResult<Map<String, Object>> {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 因成交来源不明或成交链不完整而排除的账户与交易对数量。
     */
    private final int incompleteCount;

    /** Number of returned trade cycles reconstructed from source-less legacy fills. */
    private final int inferredCount;

    public TradeHistoryPageVo(List<Map<String, Object>> rows, long total, int incompleteCount) {
        this(rows, total, incompleteCount, 0);
    }

    public TradeHistoryPageVo(List<Map<String, Object>> rows, long total, int incompleteCount, int inferredCount) {
        super(rows, total);
        this.incompleteCount = incompleteCount;
        this.inferredCount = inferredCount;
    }
}
