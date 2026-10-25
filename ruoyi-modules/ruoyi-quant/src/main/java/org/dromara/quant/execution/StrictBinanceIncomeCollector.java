package org.dromara.quant.execution;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Collects a complete bounded Binance income-history interval.
 * Saturated windows are split recursively; a saturated millisecond is rejected
 * rather than silently truncating funding/commission income events.
 */
@Service
@RequiredArgsConstructor
public class StrictBinanceIncomeCollector {
    private static final Duration WINDOW = Duration.ofDays(6);
    private static final int LIMIT = 1000;
    private static final int MAX_QUERIES = 10_000;
    @NonNull private final ExchangeGateway gateway;

    /** Collect a complete interval, preserving distinct income types sharing a transaction ID. */
    public List<ExchangeGateway.IncomeSnapshot> collect(ExchangeGateway.Credential credential, ExchangeGateway.TimeRange range) {
        if (credential == null || range == null || range.from() == null || range.to() == null
            || !range.from().isBefore(range.to()) || Duration.between(range.from(), range.to()).compareTo(Duration.ofDays(180)) > 0) {
            throw new IllegalArgumentException("Unbounded or invalid Binance income-history interval");
        }
        Map<String, ExchangeGateway.IncomeSnapshot> unique = new HashMap<>();
        int[] queryCount = {0};
        long left = range.from().toEpochMilli();
        long end = range.to().toEpochMilli();
        while (left <= end) {
            long right = Math.min(end, left + WINDOW.toMillis());
            collectWindow(credential, left, right, unique, queryCount);
            if (right == Long.MAX_VALUE) {
                break;
            }
            left = right + 1;
        }
        List<ExchangeGateway.IncomeSnapshot> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(ExchangeGateway.IncomeSnapshot::time)
            .thenComparing(ExchangeGateway.IncomeSnapshot::transactionId)
            .thenComparing(ExchangeGateway.IncomeSnapshot::incomeType));
        return List.copyOf(sorted);
    }

    private void collectWindow(ExchangeGateway.Credential credential, long leftMillis, long rightMillis,
                               Map<String, ExchangeGateway.IncomeSnapshot> unique, int[] queryCount) {
        if (++queryCount[0] > MAX_QUERIES) {
            throw new IllegalStateException("Binance income-history query budget exceeded; completeness unproven");
        }
        Instant left = Instant.ofEpochMilli(leftMillis);
        Instant right = Instant.ofEpochMilli(rightMillis);
        List<ExchangeGateway.IncomeSnapshot> page = gateway.queryIncomeHistory(credential, null, null,
            new ExchangeGateway.TimeRange(left, right), (long) LIMIT);
        if (page == null) {
            throw new IllegalStateException("Binance income-history page unavailable; completeness unproven");
        }
        if (page.size() >= LIMIT) {
            if (rightMillis - leftMillis <= 1) {
                throw new IllegalStateException("Binance income history remains saturated at millisecond precision; completeness unproven");
            }
            long middle = leftMillis + (rightMillis - leftMillis) / 2;
            collectWindow(credential, leftMillis, middle, unique, queryCount);
            collectWindow(credential, middle + 1, rightMillis, unique, queryCount);
            return;
        }
        for (ExchangeGateway.IncomeSnapshot item : page) {
            if (item == null || item.transactionId() == null || item.transactionId() <= 0
                || item.incomeType() == null || item.incomeType().isBlank()
                || item.amount() == null || item.asset() == null || !item.asset().matches("[A-Za-z0-9]{2,20}")
                || item.time() == null || item.time().toEpochMilli() < leftMillis || item.time().toEpochMilli() > rightMillis) {
                throw new IllegalStateException("Binance income event has missing identity/financial facts or lies outside the requested interval");
            }
            String key = item.incomeType() + ":" + item.transactionId();
            ExchangeGateway.IncomeSnapshot previous = unique.putIfAbsent(key, item);
            if (previous != null && !previous.equals(item)) {
                throw new IllegalStateException("Conflicting Binance income fact across history windows: incomeType="
                    + item.incomeType() + ", transactionId=" + item.transactionId());
            }
        }
    }
}
