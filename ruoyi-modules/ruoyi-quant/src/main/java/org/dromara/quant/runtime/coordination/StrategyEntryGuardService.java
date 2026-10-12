package org.dromara.quant.runtime.coordination;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 同一账户凭证、同一交易对、同一根 K 线最多开仓一次。
 *
 * <p><b>数据库约束要求（重要）：</b>必须在 quant_strategy_entry_guard 上建立
 * <pre>
 *   UNIQUE KEY uk_entry_guard (credential_id, symbol, kline_open_time)
 * </pre>
 * 唯一键不能包含 instance_id，否则不同实例可在同一根 K 线各插入一条记录，开仓唯一性被破坏。
 * 若需求为“同一根 K 线绝对只尝试一次”，请将下面的 release() 改成状态标记而不是物理删除。</p>
 */
@Service
public class StrategyEntryGuardService {
    /**
     * 用于执行需要数据库原子语义的 SQL 操作。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 创建 StrategyEntryGuardService 实例并初始化其依赖。
     */
    public StrategyEntryGuardService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 尝试占用同账户、交易对、K 线唯一开仓保护记录。
     */
    public boolean tryClaim(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime) {
        if (instanceId == null || credentialId == null || symbol == null || symbol.isBlank() || klineOpenTime == null) {
            return false;
        }
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        try {
            int affected = jdbcTemplate.update(
                "INSERT IGNORE INTO quant_strategy_entry_guard "
                    + "(credential_id, symbol, instance_id, kline_open_time, created_at) "
                    + "VALUES (?, ?, ?, ?, NOW())",
                credentialId, symbol.toUpperCase(), instanceId, openTime);
            return affected == 1;
        } catch (Exception ex) {
            // 数据库异常与"已占用"在返回值上不可区分，调用方需以业务日志判断。
            return false;
        }
    }

    /**
     * 把成功创建的订单标识回写开仓保护记录。
     */
    public void bindOrder(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime, Long orderId) {
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        jdbcTemplate.update("UPDATE quant_strategy_entry_guard SET order_id=? WHERE instance_id=? AND credential_id=? AND symbol=? AND kline_open_time=? AND (order_id IS NULL OR order_id=?)",
            orderId, instanceId, credentialId, symbol.toUpperCase(), openTime, orderId);
    }

    /**
     * 下单尚未触达交易所且明确失败时释放本根 K 线保护记录。
     */
    public void release(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime) {
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        jdbcTemplate.update(
            "DELETE FROM quant_strategy_entry_guard "
                + "WHERE instance_id=? AND credential_id=? AND symbol=? AND kline_open_time=? AND order_id IS NULL",
            instanceId, credentialId, symbol.toUpperCase(), openTime);
    }
}
