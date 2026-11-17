package org.dromara.quant.reconcile;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.reconcile.mapper.TradeHistoryCollectionLeaseMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates historical fill collection for one credential and symbol across nodes.
 */
@Service
@RequiredArgsConstructor
public class TradeHistoryCollectionLeaseService {
    private static final int LEASE_SECONDS = 90;
    private static final long RENEW_INTERVAL_SECONDS = 25;

    private final TradeHistoryCollectionLeaseMapper mapper;
    private final ScheduledExecutorService renewExecutor = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon().name("quant-trade-history-lease-renew").factory());

    public Lease acquire(Long credentialId, String symbol) {
        if (credentialId == null || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("成交历史采集租约缺少账户或交易对");
        }
        String owner = UUID.randomUUID().toString();
        mapper.ensure(credentialId, symbol);
        if (mapper.acquire(credentialId, symbol, owner, LEASE_SECONDS) != 1) {
            return null;
        }
        Long epoch = mapper.currentEpoch(credentialId, symbol, owner);
        if (epoch == null) {
            return null;
        }
        return new Lease(credentialId, symbol, owner, epoch);
    }

    @PreDestroy
    public void shutdown() {
        renewExecutor.shutdownNow();
    }

    public final class Lease implements AutoCloseable {
        private final Long credentialId;
        private final String symbol;
        private final String owner;
        private final long epoch;
        private final AtomicBoolean lost = new AtomicBoolean();
        private final ScheduledFuture<?> renewal;

        private Lease(Long credentialId, String symbol, String owner, long epoch) {
            this.credentialId = credentialId;
            this.symbol = symbol;
            this.owner = owner;
            this.epoch = epoch;
            this.renewal = renewExecutor.scheduleAtFixedRate(this::renew,
                RENEW_INTERVAL_SECONDS, RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
        }

        private void renew() {
            if (lost.get()) {
                return;
            }
            if (mapper.renew(credentialId, symbol, owner, epoch, LEASE_SECONDS) != 1) {
                lost.set(true);
            }
        }

        /**
         * Lock and verify the lease row inside the fact-write transaction.
         * The row lock prevents a takeover until that transaction commits.
         */
        public void assertOwnedForCommit() {
            if (lost.get() || !Long.valueOf(epoch).equals(mapper.lockOwned(credentialId, symbol, owner, epoch))) {
                lost.set(true);
                throw new IllegalStateException("成交历史采集租约已失效，拒绝提交旧采集结果 credentialId="
                    + credentialId + " symbol=" + symbol + " epoch=" + epoch);
            }
        }

        @Override
        public void close() {
            renewal.cancel(false);
            mapper.release(credentialId, symbol, owner, epoch);
        }
    }
}
