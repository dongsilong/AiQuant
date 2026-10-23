package org.dromara.quant.runtime;

/**
 * 开仓失败后的本地重试门控，仅限制重试频率，不改变策略入场信号。
 * <p>该对象维护单个运行实例内的退避状态；进程重启不会恢复计时，
 * 不可替代数据库中的开仓幂等键、风险检查或分布式租约。
 */
final class EntryRetryGate {
    private static final long[] DELAYS = {1000, 2000, 5000, 10000, 30000};
    private long nextCheckAt;
    private int failures;
    private boolean waitForChange;

    boolean due(long now) {
        return now >= nextCheckAt;
    }

    /**
     * 仅在退避到期时尝试继续；等待条件变化时须先重新执行容量检查。
     * 容量检查抛出异常则不会调用 reset，下一轮仍需经过退避时间。
     */
    boolean allow(long now, Runnable capacityCheck) {
        if (!due(now)) {
            return false;
        }
        if (!waitForChange) {
            return true;
        }
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
