package org.dromara.quant.runtime.coordination;

import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.runtime.coordination.mapper.StrategyRuntimeLeaseMapper;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * 基于 MySQL 的分布式策略运行租约。
 */
@Slf4j
@Service
public class StrategyRuntimeLeaseService {
    /** MyBatis 原子 SQL Mapper。 */
    private final StrategyRuntimeLeaseMapper mapper;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    /**
     * 当前节点运行租约 Owner Token 的随机前缀。
     */
    private final String ownerTokenPrefix = UUID.randomUUID().toString();

    /**
     * 创建 StrategyRuntimeLeaseService 实例并初始化其依赖。
     */
    public StrategyRuntimeLeaseService(StrategyRuntimeLeaseMapper mapper, QuantProperties properties) {
        this.mapper = java.util.Objects.requireNonNull(mapper);
        this.properties = properties;
    }

    /**
     * 获取或续期策略运行租约。
     */
    public boolean acquireOrRenew(Long instanceId, Long credentialId, String symbol) {
        if (instanceId == null || credentialId == null || symbol == null || symbol.isBlank()) {
            return false;
        }
        String normalized = symbol.toUpperCase(Locale.ROOT);
        int leaseSeconds = Math.max(15, properties.getRuntime().getLeaseSeconds());
        String ownerToken = ownerToken(instanceId);
        try {
            mapper.ensureRow(instanceId, credentialId, normalized, ownerToken, leaseSeconds);
            int updated = mapper.acquireOrRenew(instanceId, credentialId, normalized, ownerToken, leaseSeconds);
            return updated == 1;
        } catch (Exception ex) {
            log.error( "策略运行租约 SQL 执行失败 instanceId={} credentialId={} symbol={}", instanceId, credentialId, normalized, ex);
            return false;
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
