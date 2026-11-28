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
import java.util.List;
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

    /**
     * 查询等待自动派发的信号。轮次数量受策略实例数约束，按创建时间优先处理。
     */
    public List<QuantStrategyHedgeRound> findSignalReady() {
        return mapper.selectList(new LambdaQueryWrapper<QuantStrategyHedgeRound>()
            .eq(QuantStrategyHedgeRound::getStatus, STATUS_SIGNAL_READY)
            .orderByAsc(QuantStrategyHedgeRound::getCreatedAt)
            .last("LIMIT 100"));
    }

    /**
     * 使用乐观锁竞争信号接管权，只有一个节点能将 SIGNAL_READY 推进为 OPENING。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryBeginOpening(QuantStrategyHedgeRound round) {
        if (round == null || !STATUS_SIGNAL_READY.equals(round.getStatus())
            || !LEG_NOT_STARTED.equals(round.getLongLegStatus())
            || !LEG_NOT_STARTED.equals(round.getShortLegStatus())) {
            return false;
        }
        round.setStatus(STATUS_OPENING);
        try {
            save(round);
            return true;
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("双开轮次状态版本冲突")) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 结束尚未发送任何订单的无效信号，避免失效信号永久占用实例/交易对。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean abortSignalReady(QuantStrategyHedgeRound round) {
        if (round == null || !STATUS_SIGNAL_READY.equals(round.getStatus())) {
            return false;
        }
        round.setStatus(STATUS_ABORTED);
        round.setLongLegStatus(LEG_FAILED);
        round.setShortLegStatus(LEG_FAILED);
        try {
            save(round);
            return true;
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("双开轮次状态版本冲突")) {
                return false;
            }
            throw ex;
        }
    }

    /**
     * 返回仍需要核实开仓订单和持仓事实的轮次。
     */
    public java.util.List<QuantStrategyHedgeRound> findPendingRecoveryRounds() {
        return mapper.selectList(new LambdaQueryWrapper<QuantStrategyHedgeRound>()
            .in(QuantStrategyHedgeRound::getStatus, STATUS_OPENING, STATUS_DEGRADED)
            .orderByAsc(QuantStrategyHedgeRound::getUpdatedAt)
            .last("LIMIT 100"));
    }

    /**
     * 双开某条腿已核实平仓后推进轮次状态；另一条腿仍在持仓时保留 CLOSING 阻断。
     */
    @Transactional(rollbackFor = Exception.class)
    public void markLegClosed(String hedgeRoundKey, String leg) {
        if (!"LONG".equals(leg) && !"SHORT".equals(leg)) {
            throw new IllegalArgumentException("双开轮次平仓腿必须为 LONG 或 SHORT");
        }
        QuantStrategyHedgeRound round = findByRoundKey(hedgeRoundKey);
        if (round == null) {
            throw new IllegalStateException("双开平仓找不到对应轮次: " + hedgeRoundKey);
        }
        if ("LONG".equals(leg)) {
            round.setLongLegStatus(LEG_CLOSED);
        } else {
            round.setShortLegStatus(LEG_CLOSED);
        }
        boolean bothClosed = LEG_CLOSED.equals(round.getLongLegStatus())
            && LEG_CLOSED.equals(round.getShortLegStatus());
        round.setStatus(bothClosed ? STATUS_CLOSED : STATUS_CLOSING);
        save(round);
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
