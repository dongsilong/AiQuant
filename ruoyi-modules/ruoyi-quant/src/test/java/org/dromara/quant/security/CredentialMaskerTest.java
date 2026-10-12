package org.dromara.quant.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("dev")
class CredentialMaskerTest {

    @Test
    void masksApiKeyWithoutExposingSecret() {
        assertEquals("abcd****wxyz", CredentialMasker.maskApiKey("abcdefghijklmnopwxyz"));
    }

    @Test
    void masksShortApiKeyCompletely() {
        assertEquals("****", CredentialMasker.maskApiKey("short"));
    }
}
