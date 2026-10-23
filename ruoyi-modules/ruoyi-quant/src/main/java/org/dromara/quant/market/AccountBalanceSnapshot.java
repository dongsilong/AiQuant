package org.dromara.quant.market;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 可用保证金完整快照；coverage 仅含查询开始前已确认 FILLED 的本地开仓。
 */
public record AccountBalanceSnapshot(Long credentialId, BigDecimal availableBalance, Set<String> coveredExecutions, long expiresAtNanos) {
    public AccountBalanceSnapshot {
        coveredExecutions = Set.copyOf(coveredExecutions);
    }

    /**
     * 判断快照是否仍在有效期内且来源流保持健康。
     */
    public boolean fresh() {
        return availableBalance != null && availableBalance.signum() >= 0 && System.nanoTime() < expiresAtNanos;
    }
}
