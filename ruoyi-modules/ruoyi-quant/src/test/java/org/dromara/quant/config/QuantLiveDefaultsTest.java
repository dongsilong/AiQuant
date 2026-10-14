package org.dromara.quant.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** LIVE permission defaults on; independent operational and data safety gates remain mandatory. */
@Tag("dev")
class QuantLiveDefaultsTest {
    @Test
    void globalLivePermissionIsOnByDefault() {
        var config = new QuantProperties();
        assertTrue(config.getLive().isEnabled());
    }

    @Test
    void explicitOperatorOverrideCanDisableLive() {
        var config = new QuantProperties();
        config.getLive().setEnabled(false);
        assertFalse(config.getLive().isEnabled());
    }
}
