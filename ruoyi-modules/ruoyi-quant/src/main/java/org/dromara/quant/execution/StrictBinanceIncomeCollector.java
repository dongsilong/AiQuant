package org.dromara.quant.execution;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
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

    private static String invalidReason(ExchangeGateway.IncomeSnapshot item, long leftMillis, long rightMillis) {
        if (item == null) return "null_event";
        if (item.transactionId() == null) return "missing_transaction_id";
        // Binance documents tranId as unique within incomeType, but does not specify a strictly-positive range.
        // Zero is therefore not rejected solely by value; negative IDs remain invalid.
        if (item.transactionId() < 0) return "invalid_transaction_id";
        if (item.incomeType() == null || item.incomeType().isBlank()) return "missing_income_type";
        if (item.amount() == null) return "missing_amount";
        if (item.asset() == null || !item.asset().matches("[A-Za-z0-9]{2,20}")) return "missing_or_invalid_asset";
        if (item.time() == null) return "missing_event_time";
        long eventMillis = item.time().toEpochMilli();
        if (eventMillis < leftMillis || eventMillis > rightMillis) return "event_time_outside_requested_interval";
        return null;
    }

    /**
     * Stable idempotency identity for an income fact. Non-zero Binance tranId is unique
     * within incomeType; tranId=0 is represented by a fingerprint of immutable facts.
     * If two zero-ID rows are identical in every available field, Binance provides no
     * additional identifier with which to distinguish them.
     */
    public static String identityKey(ExchangeGateway.IncomeSnapshot event) {
        if (event == null || event.transactionId() == null || event.incomeType() == null || event.incomeType().isBlank()) {
            throw new IllegalArgumentException("Income event identity requires transaction ID and income type");
        }
        if (event.transactionId() != 0L) {
            return "T:" + event.incomeType() + ":" + event.transactionId();
        }
        String eventTime = event.time() == null ? "" : LocalDateTime.ofInstant(event.time(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String amount = event.amount() == null ? "" : event.amount().stripTrailingZeros().toPlainString();
        String canonical = String.join("\u001f",
            Objects.toString(event.symbol(), ""),
            event.incomeType(),
            amount,
            Objects.toString(event.asset(), ""),
            eventTime,
            Objects.toString(event.info(), ""));
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "Z:" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String safeToken(String value) {
        if (value == null || value.isBlank()) return "<missing>";
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
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
            String invalidReason = invalidReason(item, leftMillis, rightMillis);
            if (invalidReason != null) {
                // Do not log amounts, credentials, raw SDK payloads or account secrets.
                // The field-level reason is sufficient to distinguish DTO mapping bugs
                // from a real out-of-window exchange response.
                throw new IllegalStateException("Binance income event rejected: reason=" + invalidReason
                    + ", incomeType=" + safeToken(item == null ? null : item.incomeType())
                    + ", transactionId=" + (item == null ? null : item.transactionId())
                    + ", eventTime=" + (item == null ? null : item.time())
                    + ", requestedFrom=" + left + ", requestedTo=" + right);
            }
            String key = identityKey(item);
            ExchangeGateway.IncomeSnapshot previous = unique.putIfAbsent(key, item);
            if (previous != null && !previous.equals(item)) {
                throw new IllegalStateException("Conflicting Binance income fact across history windows: incomeType="
                    + item.incomeType() + ", transactionId=" + item.transactionId());
            }
        }
    }
}
