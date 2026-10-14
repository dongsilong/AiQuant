package org.dromara.quant.execution;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.execution.mapper.StrategyFillAllocationMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Read-only totals computed exclusively from immutable exchange fill attribution.
 * This is NOT a per-cycle current-position calculation.
 */
@Service
@RequiredArgsConstructor
public class StrategyFillAuditService {
    private final StrategyFillAllocationMapper mapper;

    /** Return decimal and identifier values as strings for lossless JSON clients. */
    public record Summary(String credentialId, String strategyId, String instanceId,
                          String symbol, String allocationScope,
                          String fillCount, String boughtQty, String soldQty,
                          String boughtNotional, String soldNotional,
                          String totalFees, String feeAsset, String realizedPnl) {}

    public Summary summarize(Long credentialId, Long strategyId, Long instanceId, String symbol) {
        if (credentialId == null || credentialId <= 0 || strategyId == null || strategyId <= 0
            || instanceId == null || instanceId <= 0
            || symbol == null || !symbol.matches("[a-zA-Z0-9]{2,32}")) {
            throw new IllegalArgumentException("缺少可验证的策略成交汇总查询条件");
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        Map<String,Object> r = mapper.summarizeVerifiedFills(
            credentialId, strategyId, instanceId, normalized);
        if (r == null || r.get("fill_count") == null) {
            throw new IllegalStateException("策略成交归属数据不可用，拒绝返回伪造的持仓汇总");
        }
        Object feeCount = r.get("fee_asset_count");
        if (!(feeCount instanceof Number n) || n.longValue() != 1L
            || !(r.get("fee_asset") instanceof String asset) || asset.isBlank()) {
            throw new IllegalStateException("Mixed or unknown fee assets; do not sum currencies");
        }
        return new Summary(credentialId.toString(), strategyId.toString(),
            instanceId.toString(), normalized, "LIFETIME_VERIFIED_FILLS_NOT_OPEN_POSITION",
            number(r,"fill_count"), decimal(r,"bought_qty"), decimal(r,"sold_qty"),
            decimal(r,"bought_notional"), decimal(r,"sold_notional"),
            decimal(r,"total_fees"), (String)r.get("fee_asset"), decimal(r,"realized_pnl"));
    }

    private static String decimal(Map<String,Object> values, String field) {
        Object obj = values.get(field);
        if (!(obj instanceof BigDecimal d)) {
            throw new IllegalStateException("无法核实策略成交金额字段: " + field);
        }
        return d.toPlainString();
    }

    private static String number(Map<String,Object> values, String field) {
        Object n = values.get(field);
        if (!(n instanceof Number)) {
            throw new IllegalStateException("无法核实策略成交数量字段: " + field);
        }
        return n.toString();
    }
}
