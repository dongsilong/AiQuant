package org.dromara.quant.exchange;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExchangeTradingRuleCacheTest {

    @Test
    void reusesRulesWithinEnvironmentButSeparatesLiveAndTestnet() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        ExchangeTradingRuleCache cache = new ExchangeTradingRuleCache(gateway);
        ExchangeGateway.Credential live = new ExchangeGateway.Credential("live-key", "secret", "HMAC", "LIVE");
        ExchangeGateway.Credential testnet = new ExchangeGateway.Credential("test-key", "secret", "HMAC", "TESTNET");
        ExchangeGateway.SymbolRule liveRule = rule("BTCUSDT", "TRADING");
        ExchangeGateway.SymbolRule testnetRule = rule("BTCUSDT", "TRADING");

        when(gateway.queryTradingRule(any(ExchangeGateway.Credential.class), eq("BTCUSDT")))
            .thenAnswer(invocation -> "TESTNET".equals(invocation.getArgument(0, ExchangeGateway.Credential.class).runMode())
                ? testnetRule : liveRule);

        assertSame(liveRule, cache.get("LIVE", live, "btcusdt"));
        assertSame(liveRule, cache.get("LIVE", live, "BTCUSDT"));
        assertSame(testnetRule, cache.get("TESTNET", testnet, "BTCUSDT"));

        verify(gateway, times(2)).queryTradingRule(any(ExchangeGateway.Credential.class), eq("BTCUSDT"));
    }

    @Test
    void doesNotCacheFailedRuleLookup() {
        ExchangeGateway gateway = mock(ExchangeGateway.class);
        ExchangeTradingRuleCache cache = new ExchangeTradingRuleCache(gateway);
        ExchangeGateway.Credential live = new ExchangeGateway.Credential("live-key", "secret", "HMAC", "LIVE");
        ExchangeGateway.SymbolRule rule = rule("ETHUSDT", "TRADING");

        when(gateway.queryTradingRule(live, "ETHUSDT"))
            .thenThrow(new IllegalStateException("temporary exchange failure"))
            .thenReturn(rule);

        assertThrows(IllegalStateException.class, () -> cache.get("LIVE", live, "ETHUSDT"));
        assertSame(rule, cache.get("LIVE", live, "ETHUSDT"));
        verify(gateway, times(2)).queryTradingRule(live, "ETHUSDT");
    }

    private static ExchangeGateway.SymbolRule rule(String symbol, String status) {
        return new ExchangeGateway.SymbolRule(symbol, "USDT", 2, 3, new BigDecimal("0.01"),
            new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("5"), status);
    }
}
