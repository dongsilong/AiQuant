package org.dromara.quant.runtime;

/** 开仓重试门控，仅控制执行频率，不改变策略信号。 */
final class EntryRetryGate {
    private static final long[] DELAYS = {1000, 2000, 5000, 10000, 30000};
    private long nextCheckAt;
    private int failures;
    private boolean waitForChange;

    boolean due(long now) { return now >= nextCheckAt; }

    boolean allow(long now, Runnable capacityCheck) {
        if (!due(now)) { return false; }
        if (!waitForChange) { return true; }
        // 校验失败也必须退避，不能由下一条行情立即触发重查。
        nextCheckAt = now + 5000;
        capacityCheck.run();
        reset();
        return true;
    }

    void failed(long now, boolean waitForChange) {
        this.waitForChange = waitForChange;
        long delay = waitForChange ? 5000 : DELAYS[Math.min(failures, DELAYS.length - 1)];
        failures = Math.min(failures + 1, DELAYS.length);
        nextCheckAt = now + delay;
    }

    void reset() {
        nextCheckAt = 0;
        failures = 0;
        waitForChange = false;
    }
}
