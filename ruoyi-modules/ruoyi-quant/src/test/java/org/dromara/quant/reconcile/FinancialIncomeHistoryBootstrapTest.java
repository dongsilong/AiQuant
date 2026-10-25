package org.dromara.quant.reconcile;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("dev")
class FinancialIncomeHistoryBootstrapTest {
    @Test
    void missingCursorBootstrapsMaximumRetainedIncomeHistoryInsteadOfSchedulerWindow() {
        Instant now = Instant.parse("2026-10-25T05:00:00Z");

        assertEquals(now.minusSeconds(180L * 24 * 60 * 60),
            TradingReconciliationService.initialRetainedHistoryStart(now, null));
    }

    @Test
    void existingCursorIsPreservedExactlyInUtc() {
        Instant now = Instant.parse("2026-10-25T05:00:00Z");
        LocalDateTime cursor = LocalDateTime.of(2026, 10, 20, 1, 2, 3, 456_000_000);

        assertEquals(cursor.toInstant(ZoneOffset.UTC),
            TradingReconciliationService.initialIncomeHistoryStart(now, cursor));
    }
}
