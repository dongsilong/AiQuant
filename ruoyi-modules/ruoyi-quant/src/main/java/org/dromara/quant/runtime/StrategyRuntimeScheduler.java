package org.dromara.quant.runtime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Strategy supervisor and fact-reconciliation timer adapter.
 *
 * <p>All trade decisions and session state remain inside StrategyRuntimeService,
 * so changing scheduling cannot bypass leases, risk, or entry idempotency.
 */
@Component
public class StrategyRuntimeScheduler {
    private final StrategyRuntimeService runtime;

    public StrategyRuntimeScheduler(StrategyRuntimeService runtime) {
        this.runtime = runtime;
    }

    @Scheduled(fixedDelayString = "#{@quantProperties.runtime.tickSeconds * 1000}", initialDelay = 5000)
    public void supervise() {
        runtime.supervise();
    }

    @Scheduled(fixedDelayString = "#{@quantProperties.reconcile.intervalSeconds * 1000}", initialDelay = 15000)
    public void reconcilePositions() {
        runtime.reconcileRunningPositions();
    }
}
