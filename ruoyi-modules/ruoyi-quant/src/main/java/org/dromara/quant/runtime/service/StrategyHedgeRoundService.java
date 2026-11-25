package org.dromara.quant.runtime.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.dromara.quant.runtime.mapper.QuantStrategyHedgeRoundMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

/**
 * 双开轮次级持久化和并发入场保护。
 */
@RequiredArgsConstructor
@Service
public class StrategyHedgeRoundService {
    public static final String STATUS_OPENING = "OPENING";
    public static final String STATUS_SIGNAL_READY = "SIGNAL_READY";
    public static final String STATUS_DEGRADED = "DEGRADED";
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSING = "CLOSING";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_ABORTED = "ABORTED";

    public static final String LEG_NOT_STARTED = "NOT_STARTED";
    public static final String LEG_DISPATCHING = "DISPATCHING";
    public static final String LEG_ACCEPTED = "ACCEPTED";
    public static final String LEG_UNKNOWN = "UNKNOWN";
    public static final String LEG_PARTIALLY_FILLED = "PARTIALLY_FILLED";
    public static final String LEG_OPEN = "OPEN";
    public static final String LEG_FAILED = "FAILED";
    public static final String LEG_CLOSING = "CLOSING";
    public static final String LEG_CLOSED = "CLOSED";

    private final QuantStrategyHedgeRoundMapper mapper;

    /**
     * 原子创建一轮双开。唯一键冲突表示同一 K 线已尝试，或该实例仍有未结束轮次。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean claim(QuantStrategyHedgeRound round) {
        if (round == null || round.getStrategyId() == null || round.getInstanceId() == null
            || round.getCredentialId() == null || round.getSymbol() == null || round.getSymbol().isBlank()
            || round.getEntryKlineOpenTime() == null || round.getEntryEquitySnapshot() == null
            || round.getEntryEquitySnapshot().signum() <= 0) {
            throw new IllegalArgumentException("双开轮次缺少有效的策略、账户、交易对、K线时间或总权益快照");
        }
        round.setSymbol(round.getSymbol().trim().toUpperCase(Locale.ROOT));
        if (round.getHedgeRoundKey() == null || round.getHedgeRoundKey().isBlank()) {
            round.setHedgeRoundKey(UUID.randomUUID().toString());
        }
        if (round.getStatus() == null) {
            round.setStatus(STATUS_OPENING);
        }
        if (round.getLongLegStatus() == null) {
            round.setLongLegStatus(LEG_NOT_STARTED);
        }
        if (round.getShortLegStatus() == null) {
            round.setShortLegStatus(LEG_NOT_STARTED);
        }
        if (round.getVersion() == null) {
            round.setVersion(0);
        }
        if (STATUS_SIGNAL_READY.equals(round.getStatus())
            && (round.getReferencePrice() == null || round.getReferencePrice().signum() <= 0
                || round.getSignalEventTime() == null)) {
            throw new IllegalArgumentException("双开待执行信号缺少有效参考价格或原始事件时间");
        }
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        round.setCreatedAt(now);
        round.setUpdatedAt(now);
        try {
            return mapper.insert(round) == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public QuantStrategyHedgeRound findByRoundKey(String hedgeRoundKey) {
        if (hedgeRoundKey == null || hedgeRoundKey.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<QuantStrategyHedgeRound>()
            .eq(QuantStrategyHedgeRound::getHedgeRoundKey, hedgeRoundKey));
    }

    /**
     * 查找实例/交易对尚未结束的轮次。SIGNAL_READY、OPENING、UNKNOWN、DEGRADED 等状态都会阻止新一轮入场。
     */
    public QuantStrategyHedgeRound findBlockingRound(Long instanceId, String symbol) {
        if (instanceId == null || symbol == null || symbol.isBlank()) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<QuantStrategyHedgeRound>()
            .eq(QuantStrategyHedgeRound::getInstanceId, instanceId)
            .eq(QuantStrategyHedgeRound::getSymbol, symbol.trim().toUpperCase(Locale.ROOT))
            .notIn(QuantStrategyHedgeRound::getStatus, STATUS_CLOSED, STATUS_ABORTED)
            .last("LIMIT 1"));
    }

    /**
     * 使用乐观锁推进轮次事实；冲突时调用方必须重新读取，不得覆盖并发推进结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public void save(QuantStrategyHedgeRound round) {
        if (round == null || round.getId() == null || round.getVersion() == null) {
            throw new IllegalArgumentException("更新双开轮次必须提供主键和版本号");
        }
        Integer expectedVersion = round.getVersion();
        round.setUpdatedAt(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
        try {
            if (mapper.updateById(round) != 1) {
                throw new IllegalStateException("双开轮次状态版本冲突，拒绝覆盖最新事实: hedgeRoundKey=" + round.getHedgeRoundKey());
            }
        } catch (RuntimeException ex) {
            round.setVersion(expectedVersion);
            throw ex;
        }
    }
}
