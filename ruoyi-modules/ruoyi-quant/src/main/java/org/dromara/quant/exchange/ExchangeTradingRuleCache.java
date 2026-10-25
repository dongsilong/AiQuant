package org.dromara.quant.exchange;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 交易所交易规则的进程内共享缓存。
 *
 * <p>规则由运行环境和交易对决定，不绑定到单个 API 凭证；LIVE 与 TESTNET 必须隔离。
 * 只缓存成功取得的非空规则。过期后刷新失败时向调用方传播异常，不回退使用过期规则，
 * 避免在交易所规则变更后继续用旧精度或步进参数下单。
 */
@Component
public class ExchangeTradingRuleCache {
    private static final long TTL_NANOS = Duration.ofMinutes(5).toNanos();
    private static final int MAX_ENTRIES = 2_048;

    private final ExchangeGateway gateway;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();

    public ExchangeTradingRuleCache(ExchangeGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * 获取环境隔离的交易规则；同一环境和交易对的并发首次请求会合并到一次 REST 查询。
     */
    public ExchangeGateway.SymbolRule get(String runMode, ExchangeGateway.Credential credential, String symbol) {
        if (credential == null || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("查询交易规则需要有效凭证和交易对");
        }
        String environment = normalizeMode(runMode == null ? credential.runMode() : runMode);
        String normalizedSymbol = symbol.trim().toUpperCase(Locale.ROOT);
        Key key = new Key(environment, normalizedSymbol);
        long now = System.nanoTime();

        Entry entry = entries.compute(key, (ignored, current) -> {
            long checkedAt = System.nanoTime();
            if (current != null && checkedAt - current.expiresAtNanos() < 0) {
                return current;
            }
            ExchangeGateway.SymbolRule rule = gateway.queryTradingRule(credential, normalizedSymbol);
            if (rule == null) {
                return null;
            }
            return new Entry(rule, checkedAt + TTL_NANOS);
        });

        if (entry == null) {
            return null;
        }
        if (entries.size() > MAX_ENTRIES) {
            evictExpired();
        }
        return entry.rule();
    }

    /** 在交易规则同步或显式刷新后，可按环境和交易对主动失效。 */
    public void invalidate(String runMode, String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }
        entries.remove(new Key(normalizeMode(runMode), symbol.trim().toUpperCase(Locale.ROOT)));
    }

    /** 清除当前进程内的全部规则快照。 */
    public void invalidateAll() {
        entries.clear();
    }

    private void evictExpired() {
        long now = System.nanoTime();
        entries.entrySet().removeIf(entry -> now - entry.getValue().expiresAtNanos() >= 0);
        // 仅作内存上限保护；不影响正确性，下一次访问会重新查询规则。
        if (entries.size() > MAX_ENTRIES) {
            int excess = Math.max(0, entries.size() - MAX_ENTRIES);
            entries.keySet().stream().limit(excess).forEach(entries::remove);
        }
    }

    private static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "LIVE" : mode.trim().toUpperCase(Locale.ROOT);
    }

    private record Key(String environment, String symbol) {
    }

    private record Entry(ExchangeGateway.SymbolRule rule, long expiresAtNanos) {
    }
}
