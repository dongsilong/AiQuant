package org.dromara.quant.exchange;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合并同一账户同时在途的全量持仓查询，不缓存已完成快照。
 */
@Component
public class AccountPositionSnapshotQuery {
    private final ExchangeGateway gateway;
    private final ConcurrentHashMap<Long, CompletableFuture<List<ExchangeGateway.PositionSnapshot>>> inFlight = new ConcurrentHashMap<>();

    public AccountPositionSnapshotQuery(ExchangeGateway gateway) {
        this.gateway = gateway;
    }

    public List<ExchangeGateway.PositionSnapshot> query(Long credentialId, ExchangeGateway.Credential credential) {
        if (credentialId == null || credential == null) {
            throw new IllegalArgumentException("Credential id and exchange credential are required");
        }
        CompletableFuture<List<ExchangeGateway.PositionSnapshot>> created = new CompletableFuture<>();
        CompletableFuture<List<ExchangeGateway.PositionSnapshot>> existing = inFlight.putIfAbsent(credentialId, created);
        if (existing != null) {
            return await(existing);
        }
        try {
            List<ExchangeGateway.PositionSnapshot> snapshots = gateway.queryPositions(credential, null);
            List<ExchangeGateway.PositionSnapshot> result = List.copyOf(
                java.util.Objects.requireNonNull(snapshots, "Binance full position snapshot missing"));
            created.complete(result);
            return result;
        } catch (RuntimeException | Error failure) {
            created.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(credentialId, created);
        }
    }

    private static List<ExchangeGateway.PositionSnapshot> await(
        CompletableFuture<List<ExchangeGateway.PositionSnapshot>> future) {
        try {
            return future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }
}
