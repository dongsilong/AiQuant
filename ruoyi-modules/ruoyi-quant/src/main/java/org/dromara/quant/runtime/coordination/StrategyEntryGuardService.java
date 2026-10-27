package org.dromara.quant.runtime.coordination;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.runtime.coordination.mapper.StrategyEntryGuardMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 同一账户凭证、同一交易对、同一根 K 线最多开仓一次；持仓减少/平仓的 K 线也会被封锁。
 *
 * <p><b>数据库约束要求（重要）：</b>必须在 quant_strategy_entry_guard 上建立
 * <pre>
 *   UNIQUE KEY uk_entry_guard (credential_id, symbol, kline_open_time)
 * </pre>
 * 唯一键不能包含 instance_id，否则不同实例可在同一根 K 线各插入一条记录，开仓唯一性被破坏。
 * 若需求为“同一根 K 线绝对只尝试一次”，请将下面的 release() 改成状态标记而不是物理删除。</p>
 */
@RequiredArgsConstructor
@Service
public class StrategyEntryGuardService {
    /**
     * MyBatis Mapper preserves the database unique-key and conditional-write semantics.
     */
    private final StrategyEntryGuardMapper mapper;

    /**
     * 尝试占用同账户、交易对、K 线唯一开仓保护记录。
     */
    public boolean tryClaim(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime) {
        if (instanceId == null || credentialId == null || symbol == null || symbol.isBlank() || klineOpenTime == null) {
            return false;
        }
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        try {
            int affected = mapper.tryClaim(credentialId, symbol.toUpperCase(java.util.Locale.ROOT), instanceId, openTime);
            return affected == 1;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 仅将明确的唯一键冲突解释为已占用。INSERT IGNORE 正常情况下会返回 0；
            // 其他数据库异常必须向上传播，不能伪装成普通的同 K 线重复开仓拦截。
            return false;
        }
    }

    /**
     * 把成功创建的订单标识回写开仓保护记录。
     */
    public void bindOrder(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime, Long orderId) {
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        mapper.bindOrder(instanceId, credentialId, symbol.toUpperCase(java.util.Locale.ROOT), openTime, orderId);
    }

    /**
     * 持仓在指定 K 线上发生确认减仓/平仓后，将账户与交易对的该根 K 线永久封锁。
     */
    public void blockClosedCandle(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime) {
        if (instanceId == null || credentialId == null || symbol == null || symbol.isBlank() || klineOpenTime == null) {
            throw new IllegalArgumentException("缺少封锁平仓 K 线所需的账户、实例、交易对或时间");
        }
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        mapper.blockClosedCandle(credentialId, symbol.toUpperCase(java.util.Locale.ROOT), instanceId, openTime);
    }

    /**
     * 下单尚未触达交易所且明确失败时释放本根 K 线保护记录。
     */
    public void release(Long instanceId, Long credentialId, String symbol, Instant klineOpenTime) {
        LocalDateTime openTime = LocalDateTime.ofInstant(klineOpenTime, ZoneOffset.UTC);
        mapper.release(instanceId, credentialId, symbol.toUpperCase(java.util.Locale.ROOT), openTime);
    }
}
