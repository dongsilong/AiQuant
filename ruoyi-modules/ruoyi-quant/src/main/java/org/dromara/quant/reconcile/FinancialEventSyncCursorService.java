package org.dromara.quant.reconcile;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.reconcile.mapper.FinancialEventSyncCursorMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Advances the income-history watermark only after all financial facts commit. */
@Service
@RequiredArgsConstructor
public class FinancialEventSyncCursorService {
    private final FinancialEventSyncCursorMapper mapper;

    public void advance(Long credentialId, LocalDateTime lastCompleteTo) {
        if (credentialId == null || credentialId <= 0 || lastCompleteTo == null) {
            throw new IllegalArgumentException("Valid credential and complete income-history watermark required");
        }
        if (mapper.advanceIfBehind(credentialId, lastCompleteTo) > 0) {
            return;
        }
        LocalDateTime current = mapper.selectLastCompleteTo(credentialId);
        if (current != null && !current.isBefore(lastCompleteTo)) {
            return;
        }
        if (current == null) {
            try {
                if (mapper.insertCursor(credentialId, lastCompleteTo) == 1) {
                    return;
                }
            } catch (DuplicateKeyException concurrentInsert) {
                // Another reconciler inserted the cursor; retry with a monotonic CAS update.
            }
        }
        if (mapper.advanceIfBehind(credentialId, lastCompleteTo) > 0) {
            return;
        }
        current = mapper.selectLastCompleteTo(credentialId);
        if (current == null || current.isBefore(lastCompleteTo)) {
            throw new IllegalStateException("Binance income-history watermark did not advance monotonically for credentialId=" + credentialId);
        }
    }
}
