package org.dromara.quant.execution;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Collect a bounded authenticated Binance trade-history interval before
 * opening a position-book transaction.
 * <p>
 * Binance accountTradeList is capped at 1000 rows and limited to a 7-day
 * request interval. Full pages are split by time; a saturated millisecond
 * interval remains incomplete and is rejected rather than silently truncated.
 */
@Service
@RequiredArgsConstructor
public class StrictBinanceTradeCollector {
    private static final Duration WINDOW = Duration.ofDays(6);
    private static final int LIMIT = 1000;
    private static final int MAX_QUERIES = 10_000;
    @NonNull private final ExchangeGateway gateway;



    /**
     * 分页收集交易所成交并验证游标连续性和请求边界。
     */
    public List<ExchangeGateway.TradeSnapshot> collect(ExchangeGateway.Credential credential, String symbol, Instant from, Instant to) {
        if (credential == null || symbol == null || symbol.isBlank() || from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(Duration.ofDays(180)) > 0) {
            throw new IllegalArgumentException("Unbounded or unverified historical fill interval");
        }
        Map<Long, ExchangeGateway.TradeSnapshot> unique = new HashMap<>();
        long left = from.toEpochMilli();
        long end = to.toEpochMilli();
        int[] queryCount = {0};
        while (left <= end) {
            long right = Math.min(end, left + WINDOW.toMillis());
            collectWindow(credential, symbol, left, right, unique, queryCount);
            if (right == Long.MAX_VALUE) {
                break;
            }
            left = right + 1;
        }
        List<ExchangeGateway.TradeSnapshot> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(ExchangeGateway.TradeSnapshot::time).thenComparing(ExchangeGateway.TradeSnapshot::tradeId));
        return List.copyOf(sorted);
    }

    private void collectWindow(ExchangeGateway.Credential credential, String symbol, long leftMillis, long rightMillis,
                               Map<Long, ExchangeGateway.TradeSnapshot> unique, int[] queryCount) {
        if (++queryCount[0] > MAX_QUERIES) {
            throw new IllegalStateException("Binance trade history query budget exceeded; history completeness unproven");
        }
        Instant left = Instant.ofEpochMilli(leftMillis);
        Instant right = Instant.ofEpochMilli(rightMillis);
        var page = gateway.queryTrades(credential, symbol, new ExchangeGateway.TimeRange(left, right));
        if (page == null) {
            throw new IllegalStateException("Binance trade history page unavailable; history completeness unproven");
        }
        if (page.size() >= LIMIT) {
            if (rightMillis - leftMillis <= 1) {
                throw new IllegalStateException("Binance trade history remains saturated at millisecond precision; history completeness unproven");
            }
            long middle = leftMillis + (rightMillis - leftMillis) / 2;
            collectWindow(credential, symbol, leftMillis, middle, unique, queryCount);
            collectWindow(credential, symbol, middle + 1, rightMillis, unique, queryCount);
            return;
        }
        for (var trade : page) {
            if (trade == null || trade.tradeId() == null || trade.tradeId() <= 0 || trade.orderId() == null || trade.orderId() <= 0
                || trade.time() == null || !symbol.equalsIgnoreCase(trade.symbol())
                || trade.time().toEpochMilli() < leftMillis || trade.time().toEpochMilli() > rightMillis) {
                throw new IllegalStateException("Exchange fill outside requested historical interval or missing exact order identity");
            }
            if (!Set.of("BUY", "SELL").contains(trade.side() == null ? "" : trade.side().toUpperCase(Locale.ROOT))
                || trade.quantity() == null || trade.quantity().signum() <= 0
                || trade.price() == null || trade.price().signum() <= 0
                || trade.commission() == null || trade.commission().signum() < 0
                || trade.realizedPnl() == null
                || trade.commissionAsset() == null || !trade.commissionAsset().matches("[A-Za-z0-9]{2,20}")) {
                throw new IllegalStateException("Binance fill is missing quantity, price, side, commission, fee asset or realized PnL; history is not safe to apply");
            }
            var prior = unique.putIfAbsent(trade.tradeId(), trade);
            if (prior != null && !prior.equals(trade)) {
                throw new IllegalStateException("Conflicting Binance TradeId across history windows");
            }
        }
    }
}
