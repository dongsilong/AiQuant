package org.dromara.quant.market;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 账户资金快照；coverage 仅含查询开始前已确认 FILLED 的本地开仓。
 */
public record AccountBalanceSnapshot(Long credentialId, BigDecimal availableBalance, BigDecimal totalEquity, Set<String> coveredExecutions, long expiresAtNanos) {
    public AccountBalanceSnapshot(Long credentialId, BigDecimal availableBalance, Set<String> coveredExecutions, long expiresAtNanos) {
        this(credentialId, availableBalance, null, coveredExecutions, expiresAtNanos);
    }

    public AccountBalanceSnapshot {
        coveredExecutions = Set.copyOf(coveredExecutions);
    }

    /**
     * 判断可用余额快照是否仍在有效期内。
     */
    public boolean fresh() {
        return availableBalance != null && availableBalance.signum() >= 0 && System.nanoTime() < expiresAtNanos;
    }

    /**
     * 判断账户总权益是否可用于资金比例计算。
     */
    public boolean hasTotalEquity() {
        return totalEquity != null && totalEquity.signum() > 0;
    }
}
