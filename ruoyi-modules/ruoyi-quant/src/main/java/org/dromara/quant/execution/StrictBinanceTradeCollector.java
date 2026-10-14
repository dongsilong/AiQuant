package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collect a bounded authenticated Binance trade-history interval before
 * opening a position-book transaction.
 *
 * Binance accountTradeList is capped at 1000 rows and limited to a 7-day
 * request interval. An exactly full page is NOT proof of complete history:
 * this reader rejects it instead of silently omitting the next page.
 */
@Service
public class StrictBinanceTradeCollector {
    private static final Duration WINDOW=Duration.ofDays(6);
    private static final int LIMIT=1000;
    private final ExchangeGateway gateway;

    public StrictBinanceTradeCollector(ExchangeGateway gateway) {
        this.gateway=Objects.requireNonNull(gateway);
    }

    public List<ExchangeGateway.TradeSnapshot> collect(ExchangeGateway.Credential credential,
                                                        String symbol, Instant from, Instant to) {
        if (credential==null || symbol==null || symbol.isBlank()
            || from==null || to==null || !from.isBefore(to)
            || Duration.between(from,to).compareTo(Duration.ofDays(180))>0) {
            throw new IllegalArgumentException("Unbounded or unverified historical fill interval");
        }
        Map<Long,ExchangeGateway.TradeSnapshot> unique=new HashMap<>();
        Instant left=from;
        while (left.isBefore(to)) {
            Instant right=left.plus(WINDOW);
            if (right.isAfter(to)) right=to;
            var page=gateway.queryTrades(credential,symbol,new ExchangeGateway.TimeRange(left,right));
            if (page==null || page.size()>=LIMIT) {
                throw new IllegalStateException("Binance trade page saturated; history completeness unproven");
            }
            for (var trade:page) {
                if (trade==null || trade.tradeId()==null || trade.time()==null
                    || !symbol.equalsIgnoreCase(trade.symbol())
                    || trade.time().isBefore(left) || trade.time().isAfter(right)) {
                    throw new IllegalStateException("Exchange fill outside requested historical interval");
                }
                var prior=unique.putIfAbsent(trade.tradeId(),trade);
                if (prior!=null && !prior.equals(trade)) {
                    throw new IllegalStateException("Conflicting Binance TradeId across history windows");
                }
            }
            // Binance may return the inclusive right boundary in both queries.
            // Duplicate TradeIds are checked above and emitted only once.
            left=right;
        }
        List<ExchangeGateway.TradeSnapshot> sorted=new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(ExchangeGateway.TradeSnapshot::time)
            .thenComparing(ExchangeGateway.TradeSnapshot::tradeId));
        return List.copyOf(sorted);
    }
}
