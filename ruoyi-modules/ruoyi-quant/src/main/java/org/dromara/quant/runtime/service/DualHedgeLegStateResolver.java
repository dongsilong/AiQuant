package org.dromara.quant.runtime.service;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * 双开单腿订单事实恢复判定。只根据已持久化/已核实事实推导状态，不执行任何交易操作。
 */
public final class DualHedgeLegStateResolver {
    public static final String NOT_DISPATCHED = "NOT_DISPATCHED";
    public static final String DISPATCHING = "DISPATCHING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String PARTIALLY_FILLED = "PARTIALLY_FILLED";
    public static final String OPEN = "OPEN";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELED = "CANCELED";
    public static final String CONFLICT = "CONFLICT";
    public static final String FILLED_POSITION_UNVERIFIED = "FILLED_POSITION_UNVERIFIED";

    private DualHedgeLegStateResolver() {
    }

    /**
     * @param dispatchStarted 轮次持久化状态是否已进入 DISPATCHING
     * @param executionStatus quant_execution_record.execution_status
     * @param orderStatus 交易所订单状态；必须来自精确订单核对或可信订单账本
     * @param executedQuantity 交易所累计成交数量
     * @param originalQuantity 订单原始数量
     * @param positionQuantity 对应 HEDGE positionSide 的当前持仓数量绝对值
     * @param positionSnapshotComplete 是否取得完整、可信的账户持仓快照
     * @param identityConflict 执行记录、订单身份、方向或归属是否发生冲突
     */
    public static String resolve(boolean dispatchStarted, String executionStatus, String orderStatus,
                                 BigDecimal executedQuantity, BigDecimal originalQuantity,
                                 BigDecimal positionQuantity, boolean positionSnapshotComplete,
                                 boolean identityConflict) {
        if (identityConflict) {
            return CONFLICT;
        }

        BigDecimal executed = positiveOrZero(executedQuantity);
        BigDecimal original = positiveOrZero(originalQuantity);
        BigDecimal position = positiveOrZero(positionQuantity);
        String order = normalize(orderStatus);
        String execution = normalize(executionStatus);

        if (original.signum() > 0 && executed.compareTo(original) > 0
            || positionSnapshotComplete && position.signum() > 0 && executed.signum() == 0) {
            return CONFLICT;
        }

        if (order != null) {
            if ("REJECTED".equals(order)) {
                return executed.signum() > 0 ? PARTIALLY_FILLED : REJECTED;
            }
            if ("CANCELED".equals(order) || "EXPIRED".equals(order) || "EXPIRED_IN_MATCH".equals(order)) {
                if (executed.signum() > 0) {
                    return PARTIALLY_FILLED;
                }
                return CANCELED;
            }
            if ("PARTIALLY_FILLED".equals(order) || executed.signum() > 0 && original.signum() > executed.signum()) {
                return PARTIALLY_FILLED;
            }
            if ("FILLED".equals(order) || original.signum() > 0 && executed.compareTo(original) >= 0) {
                if (!positionSnapshotComplete) {
                    return FILLED_POSITION_UNVERIFIED;
                }
                return position.signum() > 0 ? OPEN : CONFLICT;
            }
            if ("NEW".equals(order) || "PENDING_NEW".equals(order)) {
                return ACCEPTED;
            }
        }

        if (positionSnapshotComplete && position.signum() > 0) {
            // 持仓存在但没有可归因的开仓订单事实，不能把它认领给本轮。
            return CONFLICT;
        }
        if ("PENDING".equals(execution)) {
            return DISPATCHING;
        }
        if ("UNKNOWN".equals(execution)) {
            return UNKNOWN;
        }
        if ("CONFIRMED".equals(execution) || "RECOVERED".equals(execution)) {
            return ACCEPTED;
        }
        if ("FAILED".equals(execution)) {
            return NOT_DISPATCHED;
        }
        return dispatchStarted ? UNKNOWN : NOT_DISPATCHED;
    }

    private static BigDecimal positiveOrZero(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
