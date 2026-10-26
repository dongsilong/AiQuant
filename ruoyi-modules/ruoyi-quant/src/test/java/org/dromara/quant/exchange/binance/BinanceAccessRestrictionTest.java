package org.dromara.quant.exchange.binance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 451 is an access restriction, not a transient network failure or timestamp error.
 */
class BinanceAccessRestrictionTest {

    @Test
    void classifiesOnlyLegalAccessRestrictionAsNonRetryable() {
        assertTrue(BinanceGatewayAdapter.isAccessRestrictedStatus(451));
        assertFalse(BinanceGatewayAdapter.isAccessRestrictedStatus(400));
        assertFalse(BinanceGatewayAdapter.isAccessRestrictedStatus(418));
        assertFalse(BinanceGatewayAdapter.isAccessRestrictedStatus(429));
        assertFalse(BinanceGatewayAdapter.isAccessRestrictedStatus(503));
    }
}
