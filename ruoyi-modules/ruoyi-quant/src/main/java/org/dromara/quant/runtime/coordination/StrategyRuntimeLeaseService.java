package org.dromara.quant.runtime.coordination;

import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * 基于 MySQL 的分布式策略运行租约。
 */
@Slf4j
@Service
public class StrategyRuntimeLeaseService {
    /**
     * 用于执行需要数据库原子语义的 SQL 操作。
     */
    private final JdbcTemplate jdbcTemplate;
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
    public StrategyRuntimeLeaseService(JdbcTemplate jdbcTemplate, QuantProperties properties) {
        // 使用独立模板，避免修改其他业务共享模板的超时。
        this.jdbcTemplate = new JdbcTemplate(java.util.Objects.requireNonNull(jdbcTemplate.getDataSource()));
        this.jdbcTemplate.setQueryTimeout(3);
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
            jdbcTemplate.update("INSERT IGNORE INTO quant_strategy_runtime_lease (instance_id, credential_id, symbol, owner_token, lease_until, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL ? SECOND), NOW(), NOW())", instanceId, credentialId, normalized, ownerToken, leaseSeconds);
            int updated = jdbcTemplate.update("UPDATE quant_strategy_runtime_lease SET instance_id=?, owner_token=?, lease_until=DATE_ADD(NOW(), INTERVAL ? SECOND), " +
                "update_time=NOW() " + "WHERE credential_id=? AND symbol=? AND (owner_token=? OR lease_until<NOW())", instanceId, ownerToken, leaseSeconds, credentialId,
                normalized, ownerToken);
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
        jdbcTemplate.update("DELETE FROM quant_strategy_runtime_lease WHERE credential_id=? AND symbol=? AND owner_token=?", credentialId, symbol.toUpperCase(Locale.ROOT),
            ownerToken(instanceId));
    }

    /**
     * 生成当前应用节点的租约 Owner Token。
     */
    private String ownerToken(Long instanceId) {
        return ownerTokenPrefix + ":" + instanceId;
    }
}
