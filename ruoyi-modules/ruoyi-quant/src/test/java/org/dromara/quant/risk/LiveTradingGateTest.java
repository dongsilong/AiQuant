package org.dromara.quant.risk;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("dev")
class LiveTradingGateTest {
    @Test
    void accountTransferCapabilityIsNotATradingPrerequisite() {
        var checkpoint = new LiveTradingGate.Checkpoint(true, true, true, false, true, true, true, true, true);
        assertDoesNotThrow(() -> new LiveTradingGate().check(checkpoint));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 4, 5, 6, 7, 8})
    void removingTransferCheckDoesNotBypassOtherTradingRequirements(int missing) {
        boolean[] values = {true, true, true, false, true, true, true, true, true};
        values[missing] = false;
        var checkpoint = new LiveTradingGate.Checkpoint(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7], values[8]);
        assertThrows(LiveTradingGate.LiveTradingBlockedException.class, () -> new LiveTradingGate().check(checkpoint));
    }


    @Test
    void rejectsLiveTradingWhenAnyGateIsMissing() {
        LiveTradingGate gate = new LiveTradingGate();
        LiveTradingGate.Checkpoint checkpoint = new LiveTradingGate.Checkpoint(true, false, true, true, true, true, true, true, true);

        assertThrows(LiveTradingGate.LiveTradingBlockedException.class, () -> gate.check(checkpoint));
    }

    @Test
    void acceptsLiveTradingWhenAllGatesAreReady() {
        LiveTradingGate gate = new LiveTradingGate();
        LiveTradingGate.Checkpoint checkpoint = new LiveTradingGate.Checkpoint(true, true, true, true, true, true, true, true, true);

        assertDoesNotThrow(() -> gate.check(checkpoint));
    }
}
