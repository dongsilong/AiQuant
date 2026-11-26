package org.dromara.quant.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Deterministic UUID identity for one leg of a dual-hedge round.
 * The persisted position_cycle_key columns are varchar(36), so the raw
 * round key and leg name must not be concatenated into the stored value.
 */
public final class DualHedgePositionCycleKey {
    private DualHedgePositionCycleKey() {
    }

    public static String of(String hedgeRoundKey, String positionSide) {
        if (hedgeRoundKey == null || hedgeRoundKey.isBlank() || positionSide == null) {
            throw new IllegalArgumentException("双开轮次或持仓方向缺失");
        }
        String leg = positionSide.toUpperCase(Locale.ROOT);
        if (!"LONG".equals(leg) && !"SHORT".equals(leg)) {
            throw new IllegalArgumentException("双开持仓方向必须为 LONG 或 SHORT");
        }
        return UUID.nameUUIDFromBytes((hedgeRoundKey.trim() + ":" + leg).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static String fromOpenIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        int operationSeparator = idempotencyKey.lastIndexOf(':');
        if (operationSeparator <= 0 || !"OPEN".equals(idempotencyKey.substring(operationSeparator + 1))) {
            return null;
        }
        int legSeparator = idempotencyKey.lastIndexOf(':', operationSeparator - 1);
        if (legSeparator <= 0) {
            return null;
        }
        String leg = idempotencyKey.substring(legSeparator + 1, operationSeparator);
        if (!"LONG".equals(leg) && !"SHORT".equals(leg)) {
            return null;
        }
        return of(idempotencyKey.substring(0, legSeparator), leg);
    }
}
