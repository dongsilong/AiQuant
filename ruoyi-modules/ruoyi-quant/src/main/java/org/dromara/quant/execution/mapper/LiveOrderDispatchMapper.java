package org.dromara.quant.execution.mapper;

import org.apache.ibatis.annotations.*;

/**
 * Durable account+symbol dispatch fence. A claimed guard has NO TTL takeover.
 * Unknown remote outcomes can only be unlocked by a separately verified recovery.
 */
@Mapper
public interface LiveOrderDispatchMapper {

    int ensureGuard(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    /**
     * Immutable approved desired configuration, created in the same transaction
     * as the account-symbol dispatch claim. Concurrent contradictory nodes
     * cannot silently change each other's margin or leverage settings.
     */

    int ensureSettingsIntent(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("marginMode") String marginMode, @Param("leverage") int leverage);

    long matchingSettingsIntent(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("marginMode") String marginMode, @Param("leverage") int leverage);

    int claim(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key);

    /**
     * Strategy dispatch claim requires a simultaneously visible, unexpired lease
     * generation. A stale node cannot claim after lease takeover.
     * The guard remains held until exact recovery; it is not a Binance-side fence.
     */

    int claimWithLease(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key, @Param("instanceId") Long instanceId,
                       @Param("epoch") Long epoch);

    int insertTicket(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key, @Param("operation") String operation, @Param(
        "clientOrderId") String clientOrderId, @Param("instanceId") Long instanceId, @Param("epoch") Long epoch);

    /**
     * Pre-send strategy provenance is inserted in the SAME transaction as
     * the durable dispatch ticket, prior to any remote Binance side effect.
     */

    int insertPreDispatchIntent(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key, @Param("clientOrderId") String clientOrderId,
                                @Param("strategyId") Long strategyId, @Param("instanceId") Long instanceId, @Param("operation") String operation,
                                @Param("positionSide") String positionSide, @Param("exchangeSide") String exchangeSide, @Param("epoch") Long epoch);

    int acknowledge(@Param("credentialId") Long credentialId, @Param("key") String key, @Param("orderId") Long orderId);

    /**
     * Account trading settings have no exchange order ID. Complete the
     * persistent CONFIG ticket only after BOTH margin and leverage SDK
     * calls have returned explicit successful responses.
     */

    int acknowledgeConfiguration(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key);

    /**
     * Recover an uncertain configuration ticket only when the exact durable
     * desired settings intent and the account-symbol guard still match.
     */
    int recoverConfiguration(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key,
                             @Param("clientOrderId") String clientOrderId, @Param("marginMode") String marginMode, @Param("leverage") int leverage);

    int notSent(@Param("credentialId") Long credentialId, @Param("key") String key);

    int unknown(@Param("credentialId") Long credentialId, @Param("key") String key);

    int release(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key);

    int recoverVerified(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("key") String key, @Param("clientOrderId") String clientOrderId, @Param(
        "orderId") Long orderId);

    /**
     * Only the active account-symbol slot is eligible for fact-proven protection recovery.
     * It must match the exact client algo ID and operation type.
     */

    String activeProtectionTicket(@Param("credentialId") Long credentialId, @Param("symbol") String symbol, @Param("clientOrderId") String clientOrderId,
                                  @Param("operation") String operation);

    java.util.Map<String, Object> activeDispatch(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    java.util.List<java.util.Map<String, Object>> activeProtectionCancels(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    java.util.List<java.util.Map<String, Object>> activeProtectionDispatches(@Param("credentialId") Long credentialId, @Param("symbol") String symbol);

    String ticketStatus(@Param("credentialId") Long credentialId, @Param("key") String key);

    /**
     * Read-only operator queue, intentionally no TTL-based recovery or unlock.
     */

    java.util.List<java.util.Map<String, Object>> unresolvedDispatches();

}
