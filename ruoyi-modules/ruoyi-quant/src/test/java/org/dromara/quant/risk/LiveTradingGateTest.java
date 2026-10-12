package org.dromara.quant.risk;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("dev")
class LiveTradingGateTest {

    @Test
    void rejectsLiveTradingWhenAnyGateIsMissing() {
        LiveTradingGate gate = new LiveTradingGate();
        LiveTradingGate.Checkpoint checkpoint =
                new LiveTradingGate.Checkpoint(
                        true, false, true, true, true, true, true, true, true);

        assertThrows(
                LiveTradingGate.LiveTradingBlockedException.class, () -> gate.check(checkpoint));
    }

    @Test
    void acceptsLiveTradingWhenAllGatesAreReady() {
        LiveTradingGate gate = new LiveTradingGate();
        LiveTradingGate.Checkpoint checkpoint =
                new LiveTradingGate.Checkpoint(
                        true, true, true, true, true, true, true, true, true);

        assertDoesNotThrow(() -> gate.check(checkpoint));
    }
}
