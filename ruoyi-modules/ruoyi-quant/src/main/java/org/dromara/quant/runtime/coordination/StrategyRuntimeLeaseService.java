package org.dromara.quant.runtime.coordination;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLeaseMapper;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 基于 MySQL 的分布式策略运行租约。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class StrategyRuntimeLeaseService {
    /**
     * MyBatis 原子 SQL Mapper。
     */
    @NonNull private final StrategyRuntimeLeaseMapper mapper;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    /**
     * 当前节点运行租约 Owner Token 的随机前缀。
     */
    private final String ownerTokenPrefix = UUID.randomUUID().toString();

    /**
     * 获取或续期策略运行租约。
     */
    public boolean acquireOrRenew(Long instanceId, Long credentialId, String symbol, LocalDateTime credentialUpdateTime) {
        if (instanceId == null || credentialId == null || symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        int leaseSeconds = Math.max(15, properties.getRuntime().getLeaseSeconds());
        String ownerToken = ownerToken(instanceId);
        try {
            mapper.ensureRow(instanceId, credentialId, normalized, ownerToken, leaseSeconds);
            int updated = mapper.acquireOrRenew(instanceId, credentialId, normalized, ownerToken, leaseSeconds, credentialUpdateTime);
            return updated == 1;
        } catch (Exception ex) {
            log.error("策略运行租约 SQL 执行失败 instanceId={} credentialId={} symbol={}", instanceId, credentialId, normalized, ex);
            return false;
        }
    }

    /**
     * 获取或续期租约并读取数据库 fencing epoch。有效期从 SQL 操作开始计时，
     * 扣除安全余量，避免数据库响应耗时被错误计入本地缓存有效期。
     */
    public LeaseSnapshot acquireOrRenewSnapshot(Long instanceId, Long credentialId, String symbol, LocalDateTime credentialUpdateTime) {
        long startedAtNanos = System.nanoTime();
        if (!acquireOrRenew(instanceId, credentialId, symbol, credentialUpdateTime)) {
            return null;
        }
        try {
            long epoch = requireCurrentEpoch(instanceId, credentialId, symbol, credentialUpdateTime);
            long leaseNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(
                Math.max(1, Math.max(15, properties.getRuntime().getLeaseSeconds()) - 2));
            return new LeaseSnapshot(epoch, startedAtNanos + leaseNanos);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public record LeaseSnapshot(long epoch, long validUntilNanos) {
    }

    /**
     * Required at the final outbound strategy order boundary.
     * A missing/expired/mismatched owner always blocks the order; database errors fail closed.
     * This does NOT provide an atomic fence between the SQL read and a remote Binance request.
     */
    public void requireCurrentOwner(Long instanceId, Long credentialId, String symbol, LocalDateTime credentialUpdateTime) {
        requireCurrentEpoch(instanceId, credentialId, symbol, credentialUpdateTime);
    }

    /**
     * Returns the nonzero database fencing epoch for this node's current unexpired lease.
     * Calling the method twice around trade preparation detects a takeover/reacquisition.
     * It cannot fence a remote HTTP already in flight; that requires serialized dispatch.
     */
    public long requireCurrentEpoch(Long instanceId, Long credentialId, String symbol, LocalDateTime credentialUpdateTime) {
        if (instanceId == null || credentialId == null || symbol == null || symbol.isBlank()) {
            throw new IllegalStateException("策略最终下单缺少租约所有权上下文");
        }
        try {
            Long epoch = mapper.currentEpoch(instanceId, credentialId, symbol.toUpperCase(Locale.ROOT), ownerToken(instanceId), credentialUpdateTime);
            if (epoch == null || epoch <= 0) {
                throw new IllegalStateException("策略运行租约已失效或转移，拒绝最终交易所下单");
            }
            return epoch;
        } catch (RuntimeException ex) {
            throw new IllegalStateException("无法证明策略租约所有权代次，拒绝最终交易所下单", ex);
        }
    }

    /**
     * 释放当前节点持有的运行租约。
     */
    public void release(Long instanceId, Long credentialId, String symbol) {
        if (instanceId == null || credentialId == null || symbol == null) {
            return;
        }
        mapper.release(credentialId, symbol.toUpperCase(Locale.ROOT), ownerToken(instanceId));
    }

    /**
     * 生成当前应用节点的租约 Owner Token。
     */
    private String ownerToken(Long instanceId) {
        return ownerTokenPrefix + ":" + instanceId;
    }
}
