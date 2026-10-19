package org.dromara.quant.reconcile;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.reconcile.mapper.StrategyFillSyncCursorMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Advances fill-history checkpoints monotonically across concurrent reconciliation nodes. */
@Service
@RequiredArgsConstructor
public class StrategyFillSyncCursorService {
    private final StrategyFillSyncCursorMapper mapper;

    /** Advance only after a complete exchange interval has been persisted. */
    public void advance(Long credentialId, String symbol, LocalDateTime lastCompleteTo) {
        if (mapper.advanceIfBehind(credentialId, symbol, lastCompleteTo) > 0) {
            return;
        }
        LocalDateTime current = mapper.selectLastCompleteTo(credentialId, symbol);
        if (current != null && !current.isBefore(lastCompleteTo)) {
            return;
        }
        if (current == null) {
            try {
                if (mapper.insertCursor(credentialId, symbol, lastCompleteTo) == 1) {
                    return;
                }
            } catch (DuplicateKeyException concurrentInsert) {
                // Another reconciler created the cursor; continue with a guarded monotonic update.
            }
        }
        if (mapper.advanceIfBehind(credentialId, symbol, lastCompleteTo) > 0) {
            return;
        }
        current = mapper.selectLastCompleteTo(credentialId, symbol);
        if (current == null || current.isBefore(lastCompleteTo)) {
            throw new IllegalStateException("策略成交同步游标未能单调推进: credentialId=" + credentialId + ", symbol=" + symbol);
        }
    }
}
