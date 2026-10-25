package org.dromara.quant.exchange.binance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for Binance clock-offset estimation.
 */
class BinanceClockOffsetEstimatorTest {

    @Test
    void estimatesOffsetFromActualHttpRoundTripAfterSchedulerQueue() {
        // A long local scheduler wait happened before requestStartedAtMillis.
        // The wait must not be included in the RTT midpoint used for the offset.
        long serverTimeMillis = 1_000_000L;
        long requestStartedAtMillis = 15_500L;
        long responseReceivedAtMillis = 16_017L;

        assertEquals(984_242L, BinanceGatewayAdapter.estimateClockOffset(
            serverTimeMillis, requestStartedAtMillis, responseReceivedAtMillis));
    }

    @Test
    void rejectsInvalidHttpRoundTripSample() {
        assertThrows(IllegalArgumentException.class,
            () -> BinanceGatewayAdapter.estimateClockOffset(1_000L, 2_000L, 1_999L));
        assertThrows(IllegalArgumentException.class,
            () -> BinanceGatewayAdapter.estimateClockOffset(1_000L, 0L, 1L));
    }
}
