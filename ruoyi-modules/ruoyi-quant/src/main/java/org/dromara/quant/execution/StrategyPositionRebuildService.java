package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.mapper.StrategyFillJournalMapper;
import org.dromara.quant.order.domain.QuantOrder;
import org.dromara.quant.order.mapper.QuantOrderMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds strategy open inventory in isolated shadow tables. Promotion is
 * allowed only after the complete retained Binance history, immutable fill
 * provenance, fee ledger and current exchange position all agree.
 *
 * Realized PnL and the historical cycle floor are intentionally preserved:
 * Binance trade history is only retained for a bounded interval, so replaying
 * it cannot prove a lifetime realized-PnL total or lifetime cycle count.
 */
@Service
public class StrategyPositionRebuildService {
    private static final int PAGE_SIZE = 100;
    private static final int VERIFY_PAGE_SIZE = 500;
    private static final ChronoUnit TIME_PRECISION = ChronoUnit.MILLIS;

    private final StrategyPositionRebuildMapper rebuilds;
    private final StrategyFillJournalMapper journal;
    private final StrategyPositionProjectionMapper projections;
    private final StrategyPositionProjectionService projector;
    private final QuantOrderMapper orders;
    private final QuantCredentialMapper credentials;
    private final StrictBinanceTradeCollector collector;
    private final ExchangeGateway gateway;
    private final TransactionTemplate tx;

    public StrategyPositionRebuildService(StrategyPositionRebuildMapper rebuilds, StrategyFillJournalMapper journal,
                                          StrategyPositionProjectionMapper projections, StrategyPositionProjectionService projector,
                                          QuantOrderMapper orders, QuantCredentialMapper credentials,
                                          StrictBinanceTradeCollector collector, ExchangeGateway gateway,
                                          PlatformTransactionManager manager) {
        this.rebuilds = Objects.requireNonNull(rebuilds);
        this.journal = Objects.requireNonNull(journal);
        this.projections = Objects.requireNonNull(projections);
        this.projector = Objects.requireNonNull(projector);
        this.orders = Objects.requireNonNull(orders);
        this.credentials = Objects.requireNonNull(credentials);
        this.collector = Objects.requireNonNull(collector);
        this.gateway = Objects.requireNonNull(gateway);
        this.tx = new TransactionTemplate(Objects.requireNonNull(manager));
        this.tx.setTimeout(15);
    }

    /** 通过交易所完整保留窗口核对原始成交后，创建隔离重建任务。 */
    public long begin(long credentialId, String requestedSymbol) {
        if (credentialId <= 0 || requestedSymbol == null || !requestedSymbol.matches("[A-Za-z0-9]{2,32}")) {
            throw new IllegalArgumentException("有效的 credentialId 和 symbol 是必需的");
        }
        String symbol = requestedSymbol.toUpperCase(Locale.ROOT);
        QuantCredential credential = credentials.selectById(credentialId);
        if (credential == null || !("LIVE".equalsIgnoreCase(credential.getRunMode())
            || "TESTNET".equalsIgnoreCase(credential.getRunMode()))) {
            throw new IllegalStateException("策略库存重建仅允许 Binance LIVE 或 TESTNET 凭证");
        }
        if (journal.countUnresolvedByScope(credentialId, symbol) != 0) {
            throw new IllegalStateException("该账户交易对仍有未归属或冲突成交，必须先处理原始成交缺口");
        }

        Instant historyTo = exchangeNow(credential);
        Instant historyFrom = historyTo.minusSeconds(180L * 24 * 60 * 60);
        List<ExchangeGateway.TradeSnapshot> exchangeFills = collector.collect(
            toExchangeCredential(credential), symbol, historyFrom, historyTo);
        long sourceMaxId = journal.maxIdByScope(credentialId, symbol);
        LocalDateTime from = utc(historyFrom);
        LocalDateTime to = utc(historyTo);
        long sourceCount = journal.countAttributedInWindow(credentialId, symbol, sourceMaxId, from, to);
        verifyJournalMatchesExchange(credentialId, symbol, sourceMaxId, from, to, sourceCount, exchangeFills);

        long runId = IdWorker.getId();
        String activeKey = credentialId + ":" + symbol;
        try {
            tx.executeWithoutResult(status -> {
                if (rebuilds.insertRun(runId, credentialId, symbol, activeKey, from, to, sourceMaxId, sourceCount) != 1
                    || rebuilds.seedInventory(runId, credentialId, symbol) < 0) {
                    throw new IllegalStateException("无法创建策略库存隔离重建任务");
                }
            });
        } catch (DuplicateKeyException activeRun) {
            throw new IllegalStateException("该凭证和交易对已有未完成的库存重建任务", activeRun);
        }
        return runId;
    }

    /** 按成交时间和成交 ID 稳定分页重放一个批次。 */
    public RebuildProgress processNext(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("有效的重建任务 ID 是必需的");
        }
        try {
            return tx.execute(status -> {
                Map<String, Object> run = rebuilds.lockRun(runId);
                requireRunStatus(run, "BUILDING");
                long credentialId = number(run, "credential_id");
                String symbol = text(run, "symbol");
                long maxId = number(run, "source_max_journal_id");
                LocalDateTime from = dateTime(run, "history_from");
                LocalDateTime to = dateTime(run, "history_to");
                long sourceCount = number(run, "source_fill_count");
                long processed = number(run, "processed_count");
                LocalDateTime cursorTime = nullableDateTime(run, "cursor_trade_time");
                Long cursorId = nullableLong(run, "cursor_trade_id");

                assertSourceStable(credentialId, symbol, maxId, from, to, sourceCount);
                List<StrategyFillJournalRecord> page = journal.selectAttributedPage(
                    credentialId, symbol, maxId, from, to, cursorTime, cursorId, PAGE_SIZE);
                if (page == null) {
                    throw new IllegalStateException("成交日志分页查询返回 null");
                }
                if (page.isEmpty()) {
                    if (processed != sourceCount || rebuilds.countFills(runId) != sourceCount
                        || rebuilds.countInvalidOpenInventory(runId) != 0) {
                        throw new IllegalStateException("历史成交重放数量或影子库存校验失败");
                    }
                    if (rebuilds.markReady(runId) != 1) {
                        throw new IllegalStateException("重建任务状态无法推进到 READY");
                    }
                    return new RebuildProgress(Long.toString(runId), "READY", processed, sourceCount,
                        cursorTime == null ? null : cursorTime.toString(), cursorId);
                }

                LocalDateTime lastTime = cursorTime;
                Long lastId = cursorId;
                for (StrategyFillJournalRecord record : page) {
                    projectIntoShadow(runId, credentialId, symbol, record);
                    lastTime = record.getTradeTime();
                    lastId = record.getExchangeTradeId();
                }
                if (rebuilds.updateProgress(runId, page.size(), lastTime, lastId) != 1) {
                    throw new IllegalStateException("重建批次游标更新失败");
                }
                return new RebuildProgress(Long.toString(runId), "BUILDING", processed + page.size(),
                    sourceCount, lastTime == null ? null : lastTime.toString(), lastId);
            });
        } catch (RuntimeException ex) {
            failRun(runId, ex.getMessage());
            throw ex;
        }
    }

    /** 对照 Binance 成交历史和实时持仓；只有全部一致才原子提升影子库存。 */
    public RebuildProgress verifyAndPromote(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("有效的重建任务 ID 是必需的");
        }
        Map<String, Object> initialRun = rebuilds.selectRun(runId);
        requireRunStatus(initialRun, "READY");
        try {
            Map<String, Object> run = initialRun;
            
            long credentialId = number(run, "credential_id");
            String symbol = text(run, "symbol");
            long maxId = number(run, "source_max_journal_id");
            long sourceCount = number(run, "source_fill_count");
            LocalDateTime from = dateTime(run, "history_from");
            QuantCredential credential = credentials.selectById(credentialId);
            if (credential == null || !("LIVE".equalsIgnoreCase(credential.getRunMode())
                || "TESTNET".equalsIgnoreCase(credential.getRunMode()))) {
                throw new IllegalStateException("重建期间交易凭证已失效或运行模式改变");
            }

            Instant verifyTo = exchangeNow(credential);
            Instant fromInstant = from.toInstant(ZoneOffset.UTC);
            if (verifyTo.isBefore(fromInstant) || ChronoUnit.SECONDS.between(fromInstant, verifyTo) > 180L * 24 * 60 * 60) {
                throw new IllegalStateException("重建耗时超过 Binance 成交历史保留窗口，必须重新开始");
            }
            List<ExchangeGateway.TradeSnapshot> exchangeFills = collector.collect(
                toExchangeCredential(credential), symbol, fromInstant, verifyTo);
            verifyShadowMatchesExchange(runId, exchangeFills, sourceCount);

            List<ExchangeGateway.OrderSnapshot> openOrders = gateway.queryOrders(toExchangeCredential(credential), symbol);
            if (openOrders == null || !openOrders.isEmpty()) {
                throw new IllegalStateException("Binance 仍存在活动普通委托，拒绝提升策略库存");
            }
            List<ExchangeGateway.PositionSnapshot> positions = gateway.queryPositions(toExchangeCredential(credential), symbol);
            if (positions == null) {
                throw new IllegalStateException("Binance 当前持仓快照不可用，拒绝提升策略库存");
            }
            ExchangePosition actual = exchangePosition(positions, symbol);
            BigDecimal rebuiltLong = zero(rebuilds.sumOpenQuantity(runId, "LONG"));
            BigDecimal rebuiltShort = zero(rebuilds.sumOpenQuantity(runId, "SHORT"));
            BigDecimal rebuiltLongCost = zero(rebuilds.sumCostBasis(runId, "LONG"));
            BigDecimal rebuiltShortCost = zero(rebuilds.sumCostBasis(runId, "SHORT"));
            if (actual.longQuantity().compareTo(rebuiltLong) != 0 || actual.shortQuantity().compareTo(rebuiltShort) != 0) {
                throw new IllegalStateException("重建库存与 Binance 实际持仓数量不一致: exchangeLong="
                    + actual.longQuantity() + ", rebuiltLong=" + rebuiltLong
                    + ", exchangeShort=" + actual.shortQuantity() + ", rebuiltShort=" + rebuiltShort);
            }
            ExchangeGateway.SymbolRule symbolRule = gateway.queryTradingRule(toExchangeCredential(credential), symbol);
            if (symbolRule == null || symbolRule.priceTickSize() == null || symbolRule.priceTickSize().signum() <= 0
                || !withinCostBasisTick(actual.longCostBasis(), rebuiltLongCost, rebuiltLong, symbolRule.priceTickSize())
                || !withinCostBasisTick(actual.shortCostBasis(), rebuiltShortCost, rebuiltShort, symbolRule.priceTickSize())) {
                throw new IllegalStateException("重建库存加权成本与 Binance 持仓均价不一致或交易规则无法验证");
            }
            if (rebuilds.countInvalidOpenInventory(runId) != 0) {
                throw new IllegalStateException("影子库存存在缺失持仓周期或无效成本，拒绝提升");
            }

            tx.executeWithoutResult(status -> {
                Map<String, Object> locked = rebuilds.lockRun(runId);
                requireRunStatus(locked, "READY");
                assertSourceStable(credentialId, symbol, maxId, from, dateTime(locked, "history_to"), sourceCount);
                assertInventoryBaselineStable(runId);
                if (rebuilds.countFills(runId) != sourceCount
                    || rebuilds.countInvalidOpenInventory(runId) != 0
                    || rebuilds.promoteInventory(runId) < 0
                    || rebuilds.markVerified(runId, actual.longQuantity(), actual.shortQuantity(), rebuiltLong, rebuiltShort,
                        actual.longCostBasis(), actual.shortCostBasis(), rebuiltLongCost, rebuiltShortCost) != 1) {
                    throw new IllegalStateException("库存提升事务的最终 CAS 或数据校验失败");
                }
            });
            return new RebuildProgress(Long.toString(runId), "VERIFIED", sourceCount, sourceCount, null, null);
        } catch (RuntimeException ex) {
            failRun(runId, ex.getMessage());
            throw ex;
        }
    }

    /** 查询重建任务状态；不包含 API 密钥等凭证信息。 */
    public RebuildProgress status(long runId) {
        if (runId <= 0) {
            throw new IllegalArgumentException("有效的重建任务 ID 是必需的");
        }
        Map<String, Object> run = rebuilds.selectRun(runId);
        if (run == null) {
            throw new IllegalArgumentException("重建任务不存在");
        }
        Object cursorTime = run.get("cursor_trade_time");
        Object cursorId = run.get("cursor_trade_id");
        return new RebuildProgress(Long.toString(runId), text(run, "status"), number(run, "processed_count"),
            number(run, "source_fill_count"), cursorTime == null ? null : dateTime(run, "cursor_trade_time").toString(),
            cursorId == null ? null : number(run, "cursor_trade_id"));
    }

    private void projectIntoShadow(long runId, long credentialId, String symbol, StrategyFillJournalRecord record) {
        if (record == null || record.getCredentialId() == null || record.getCredentialId() != credentialId
            || record.getSymbol() == null || !symbol.equalsIgnoreCase(record.getSymbol())
            || !"ATTRIBUTED".equals(record.getAttributionStatus())
            || record.getExchangeTradeId() == null || record.getExchangeTradeId() <= 0
            || record.getExchangeOrderId() == null || record.getExchangeOrderId() <= 0
            || record.getTradeTime() == null || record.getQuantity() == null || record.getQuantity().signum() <= 0
            || record.getPrice() == null || record.getPrice().signum() <= 0
            || record.getCommission() == null || record.getCommission().signum() < 0
            || record.getRealizedPnl() == null || record.getCommissionAsset() == null
            || !record.getCommissionAsset().matches("[A-Za-z0-9]{2,20}")
            || record.getSide() == null || !("BUY".equalsIgnoreCase(record.getSide()) || "SELL".equalsIgnoreCase(record.getSide()))) {
            throw new IllegalStateException("ATTRIBUTED 成交缺少完整、可验证的 Binance 原始事实");
        }
        String feeAsset = record.getCommissionAsset().toUpperCase(Locale.ROOT);
        ExchangeGateway.TradeSnapshot trade = new ExchangeGateway.TradeSnapshot(
            symbol, record.getExchangeTradeId(), record.getExchangeOrderId(), record.getSide().toUpperCase(Locale.ROOT),
            record.getQuantity(), record.getPrice(), record.getCommission(), record.getRealizedPnl(),
            record.getTradeTime().toInstant(ZoneOffset.UTC), feeAsset);
        QuantOrder order = orders.selectOne(new LambdaQueryWrapper<QuantOrder>()
            .eq(QuantOrder::getCredentialId, credentialId)
            .eq(QuantOrder::getSymbol, symbol)
            .eq(QuantOrder::getExchangeOrderId, record.getExchangeOrderId()));
        if (order == null) {
            throw new IllegalStateException("成交对应的本地订单不存在，不能重建策略归属: tradeId=" + record.getExchangeTradeId());
        }
        StrategyPositionProjectionService.VerifiedAttribution attribution =
            projector.resolveVerifiedAttribution(credentialId, order, trade);

        Map<String, Object> fill = projections.existing(credentialId, symbol, record.getExchangeTradeId());
        if (fill == null) {
            throw new IllegalStateException("ATTRIBUTED 成交缺少已提交的策略成交投影事件");
        }
        long strategyId = number(fill, "strategy_id");
        long instanceId = number(fill, "strategy_instance_id");
        long cycleId = number(fill, "cycle_id");
        if (!Objects.equals(order.getStrategyId(), strategyId) || !Objects.equals(order.getStrategyInstanceId(), instanceId)
            || number(fill, "exchange_order_id") != record.getExchangeOrderId()
            || !attribution.positionSide().name().equals(text(fill, "position_side"))
            || !attribution.role().name().equals(text(fill, "fill_role"))
            || !sameText(record.getSide(), text(fill, "exchange_side"))
            || !sameDecimal(record.getQuantity(), decimal(fill, "quantity"))
            || !sameDecimal(record.getPrice(), decimal(fill, "fill_price"))
            || !sameDecimal(record.getCommission(), decimal(fill, "commission"))
            || !feeAsset.equalsIgnoreCase(text(fill, "commission_asset"))
            || !sameDecimal(record.getRealizedPnl(), decimal(fill, "realized_pnl"))
            || !sameTime(record.getTradeTime(), dateTime(fill, "trade_time"))) {
            throw new IllegalStateException("策略成交投影与原始成交或不可变订单意图冲突: tradeId=" + record.getExchangeTradeId());
        }

        Map<String, Object> fee = projections.existingFee(credentialId, symbol, record.getExchangeTradeId());
        if (fee == null || number(fee, "strategy_id") != strategyId
            || number(fee, "strategy_instance_id") != instanceId
            || number(fee, "cycle_id") != cycleId
            || number(fee, "exchange_order_id") != record.getExchangeOrderId()
            || !attribution.positionSide().name().equals(text(fee, "position_side"))
            || !sameDecimal(record.getCommission(), decimal(fee, "commission"))
            || !feeAsset.equalsIgnoreCase(text(fee, "commission_asset"))
            || !sameTime(record.getTradeTime(), dateTime(fee, "trade_time"))) {
            throw new IllegalStateException("逐笔手续费事件与原始成交或策略投影冲突: tradeId=" + record.getExchangeTradeId());
        }

        Map<String, Object> cycle = projections.cycleById(cycleId);
        LocalDateTime tradeTime = record.getTradeTime();
        if (cycle == null || number(cycle, "credential_id") != credentialId
            || !symbol.equalsIgnoreCase(text(cycle, "symbol"))
            || number(cycle, "strategy_instance_id") != instanceId
            || !attribution.positionSide().name().equals(text(cycle, "position_side"))
            || number(cycle, "cycle_number") <= 0
            || dateTime(cycle, "opened_at").isAfter(tradeTime)
            || nullableDateTime(cycle, "closed_at") != null && nullableDateTime(cycle, "closed_at").isBefore(tradeTime)) {
            throw new IllegalStateException("成交对应的策略持仓周期不存在或时间边界冲突: tradeId=" + record.getExchangeTradeId());
        }

        String side = attribution.positionSide().name();
        Map<String, Object> inventory = rebuilds.lockInventory(runId, instanceId, side);
        if (inventory == null) {
            if (rebuilds.insertInventory(runId, credentialId, symbol, instanceId, side, strategyId) != 1) {
                throw new IllegalStateException("无法创建影子策略库存");
            }
            inventory = rebuilds.lockInventory(runId, instanceId, side);
        }
        if (inventory == null || number(inventory, "strategy_id") != strategyId) {
            throw new IllegalStateException("影子库存归属与不可变成交来源不一致");
        }
        BigDecimal oldQuantity = decimal(inventory, "open_quantity");
        BigDecimal oldCost = decimal(inventory, "cost_basis");
        long oldCycleNumber = number(inventory, "cycle_number");
        Long oldCycleId = nullableLong(inventory, "cycle_id");
        LocalDateTime oldTradeTime = nullableDateTime(inventory, "last_trade_time");
        Long oldTradeId = nullableLong(inventory, "last_trade_id");
        if (oldTradeTime != null && (tradeTime.isBefore(oldTradeTime)
            || tradeTime.equals(oldTradeTime) && oldTradeId != null && record.getExchangeTradeId() <= oldTradeId)) {
            throw new IllegalStateException("同一策略库存的历史成交顺序不单调");
        }

        if (oldQuantity.signum() == 0) {
            if (attribution.role() != StrategyPositionAccounting.Role.OPEN
                || !sameTime(tradeTime, dateTime(cycle, "opened_at"))
                || number(cycle, "cycle_number") != Math.addExact(oldCycleNumber, 1)) {
                throw new IllegalStateException("历史持仓周期号与连续成交重放结果不一致");
            }
        } else if (oldCycleId == null || oldCycleId != cycleId
            || number(cycle, "cycle_number") != oldCycleNumber) {
            throw new IllegalStateException("历史成交跨越了未闭合或不连续的策略持仓周期");
        }

        StrategyPositionAccounting.State current = new StrategyPositionAccounting.State(
            oldQuantity, oldCost, BigDecimal.ZERO, BigDecimal.ZERO, oldCycleNumber);
        StrategyPositionAccounting.Fill accountingFill = new StrategyPositionAccounting.Fill(
            attribution.positionSide(), attribution.role(), trade.side(), trade.quantity(), trade.price(),
            BigDecimal.ZERO, BigDecimal.ZERO);
        StrategyPositionAccounting.State next = StrategyPositionAccounting.apply(current, accountingFill);
        long nextCycleNumber = number(cycle, "cycle_number");
        Long nextCycleId = cycleId;
        LocalDateTime closedAt = nullableDateTime(cycle, "closed_at");
        if (next.openQuantity().signum() == 0) {
            if (closedAt == null || !sameTime(closedAt, tradeTime)) {
                throw new IllegalStateException("完全平仓成交与持仓周期关闭时间不一致");
            }
            nextCycleId = null;
        }
        if (rebuilds.insertFill(runId, credentialId, symbol, strategyId, instanceId, cycleId,
            record.getExchangeTradeId(), record.getExchangeOrderId(), side, attribution.role().name(),
            trade.side(), trade.quantity(), trade.price(), trade.commission(), feeAsset,
            trade.realizedPnl(), tradeTime) != 1) {
            throw new IllegalStateException("无法保存影子成交事件");
        }
        if (rebuilds.updateInventory(runId, instanceId, side, strategyId, next.openQuantity(),
            next.costBasis(), nextCycleNumber, nextCycleId, tradeTime, record.getExchangeTradeId()) != 1) {
            throw new IllegalStateException("影子库存更新失败");
        }
    }

    private void verifyJournalMatchesExchange(long credentialId, String symbol, long maxId,
                                              LocalDateTime from, LocalDateTime to, long sourceCount,
                                              List<ExchangeGateway.TradeSnapshot> exchangeFills) {
        if (sourceCount != exchangeFills.size()) {
            throw new IllegalStateException("Binance 保留窗口成交数与 ATTRIBUTED 原始成交日志不一致: exchange="
                + exchangeFills.size() + ", journal=" + sourceCount);
        }
        Map<Long, ExchangeGateway.TradeSnapshot> unmatched = new HashMap<>();
        for (ExchangeGateway.TradeSnapshot fill : exchangeFills) {
            if (unmatched.put(fill.tradeId(), fill) != null) {
                throw new IllegalStateException("Binance 成交历史含重复 tradeId");
            }
        }
        LocalDateTime afterTime = null;
        Long afterId = null;
        long seen = 0;
        while (true) {
            List<StrategyFillJournalRecord> page = journal.selectAttributedPage(
                credentialId, symbol, maxId, from, to, afterTime, afterId, VERIFY_PAGE_SIZE);
            if (page == null) {
                throw new IllegalStateException("成交日志预检分页返回 null");
            }
            if (page.isEmpty()) {
                break;
            }
            for (StrategyFillJournalRecord record : page) {
                ExchangeGateway.TradeSnapshot exchange = unmatched.remove(record.getExchangeTradeId());
                if (exchange == null || !sameJournalFill(record, exchange)) {
                    throw new IllegalStateException("Binance 成交历史与 ATTRIBUTED 原始日志不一致: tradeId=" + record.getExchangeTradeId());
                }
                afterTime = record.getTradeTime();
                afterId = record.getExchangeTradeId();
                seen++;
            }
        }
        if (seen != sourceCount || !unmatched.isEmpty()) {
            throw new IllegalStateException("Binance 保留窗口存在未归档或未归属的成交");
        }
    }

    private void verifyShadowMatchesExchange(long runId, List<ExchangeGateway.TradeSnapshot> exchangeFills, long sourceCount) {
        if (exchangeFills.size() != sourceCount || rebuilds.countFills(runId) != sourceCount) {
            throw new IllegalStateException("重建期间 Binance 成交集合发生变化，必须重新开始");
        }
        Map<Long, ExchangeGateway.TradeSnapshot> unmatched = new HashMap<>();
        for (ExchangeGateway.TradeSnapshot fill : exchangeFills) {
            if (unmatched.put(fill.tradeId(), fill) != null) {
                throw new IllegalStateException("Binance 成交历史含重复 tradeId");
            }
        }
        LocalDateTime afterTime = null;
        Long afterId = null;
        while (true) {
            List<Map<String, Object>> page = rebuilds.selectFillPage(runId, afterTime, afterId, VERIFY_PAGE_SIZE);
            if (page == null) {
                throw new IllegalStateException("影子成交事件分页返回 null");
            }
            if (page.isEmpty()) {
                break;
            }
            for (Map<String, Object> staged : page) {
                long tradeId = number(staged, "exchange_trade_id");
                ExchangeGateway.TradeSnapshot exchange = unmatched.remove(tradeId);
                if (exchange == null || number(staged, "exchange_order_id") != exchange.orderId()
                    || !sameText(exchange.symbol(), text(staged, "symbol"))
                    || !sameText(exchange.side(), text(staged, "exchange_side"))
                    || !sameDecimal(exchange.quantity(), decimal(staged, "quantity"))
                    || !sameDecimal(exchange.price(), decimal(staged, "fill_price"))
                    || !sameDecimal(exchange.commission(), decimal(staged, "commission"))
                    || !sameText(exchange.commissionAsset(), text(staged, "commission_asset"))
                    || !sameDecimal(exchange.realizedPnl(), decimal(staged, "realized_pnl"))
                    || !sameTime(utc(exchange.time()), dateTime(staged, "trade_time"))) {
                    throw new IllegalStateException("影子成交事件与 Binance 最新历史事实冲突: tradeId=" + tradeId);
                }
                afterTime = dateTime(staged, "trade_time");
                afterId = tradeId;
            }
        }
        if (!unmatched.isEmpty()) {
            throw new IllegalStateException("重建期间出现尚未投影的新 Binance 成交");
        }
    }

    /** 在提升事务中锁定原库存行，拒绝覆盖重建期间发生变化的生产投影。 */
    private void assertInventoryBaselineStable(long runId) {
        List<Map<String, Object>> baselines = rebuilds.selectInventoryBaselineForUpdate(runId);
        if (baselines == null) {
            throw new IllegalStateException("生产库存基线锁定查询失败");
        }
        for (Map<String, Object> baseline : baselines) {
            boolean existedAtStart = number(baseline, "base_exists") == 1;
            boolean existsNow = baseline.get("live_inventory_id") != null;
            if (existedAtStart != existsNow || existedAtStart
                && !dateTime(baseline, "base_updated_at").equals(dateTime(baseline, "live_updated_at"))) {
                throw new IllegalStateException("重建期间生产策略库存发生变化，拒绝覆盖当前库存");
            }
        }
    }

    private void assertSourceStable(long credentialId, String symbol, long maxId, LocalDateTime from,
                                    LocalDateTime to, long expectedCount) {
        if (journal.countUnresolvedByScope(credentialId, symbol) != 0
            || journal.countAttributedInWindow(credentialId, symbol, maxId, from, to) != expectedCount
            || rebuilds.countJournalAfterId(credentialId, symbol, maxId) != 0) {
            throw new IllegalStateException("原始成交日志在重建期间发生变化或仍存在未归属成交");
        }
    }

    private void failRun(long runId, String reason) {
        if (runId <= 0) {
            return;
        }
        String safeReason = reason == null || reason.isBlank() ? "策略库存重建失败" : reason;
        rebuilds.markFailed(runId, safeReason.substring(0, Math.min(500, safeReason.length())));
    }

    private static void requireRunStatus(Map<String, Object> run, String status) {
        if (run == null || !status.equals(run.get("status"))) {
            throw new IllegalStateException("策略库存重建任务状态不允许此操作，required=" + status);
        }
    }

    private static ExchangePosition exchangePosition(List<ExchangeGateway.PositionSnapshot> snapshots, String symbol) {
        BigDecimal longQuantity = BigDecimal.ZERO;
        BigDecimal shortQuantity = BigDecimal.ZERO;
        BigDecimal longCostBasis = BigDecimal.ZERO;
        BigDecimal shortCostBasis = BigDecimal.ZERO;
        for (ExchangeGateway.PositionSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.symbol() == null || !symbol.equalsIgnoreCase(snapshot.symbol())
                || snapshot.quantity() == null) {
                throw new IllegalStateException("Binance 持仓快照包含缺失或跨交易对数据");
            }
            BigDecimal quantity = snapshot.quantity();
            if (quantity.signum() == 0) {
                continue;
            }
            if (snapshot.entryPrice() == null || snapshot.entryPrice().signum() <= 0) {
                throw new IllegalStateException("Binance 非零持仓缺少有效开仓均价，拒绝成本核验");
            }
            BigDecimal cost = quantity.abs().multiply(snapshot.entryPrice());
            if ("BOTH".equalsIgnoreCase(snapshot.side())) {
                if (quantity.signum() > 0) {
                    longQuantity = longQuantity.add(quantity);
                    longCostBasis = longCostBasis.add(cost);
                } else {
                    shortQuantity = shortQuantity.add(quantity.abs());
                    shortCostBasis = shortCostBasis.add(cost);
                }
            } else if ("LONG".equalsIgnoreCase(snapshot.side())) {
                longQuantity = longQuantity.add(quantity.abs());
                longCostBasis = longCostBasis.add(cost);
            } else if ("SHORT".equalsIgnoreCase(snapshot.side())) {
                shortQuantity = shortQuantity.add(quantity.abs());
                shortCostBasis = shortCostBasis.add(cost);
            } else {
                throw new IllegalStateException("Binance 持仓方向无法规范化: " + snapshot.side());
            }
        }
        return new ExchangePosition(longQuantity, shortQuantity, longCostBasis, shortCostBasis);
    }

    private static boolean withinCostBasisTick(BigDecimal exchangeCost, BigDecimal rebuiltCost,
                                               BigDecimal quantity, BigDecimal priceTick) {
        if (quantity.signum() == 0) {
            return exchangeCost.signum() == 0 && rebuiltCost.signum() == 0;
        }
        return exchangeCost.subtract(rebuiltCost).abs().compareTo(quantity.multiply(priceTick)) <= 0;
    }

    private static boolean sameJournalFill(StrategyFillJournalRecord journal, ExchangeGateway.TradeSnapshot exchange) {
        return journal != null && journal.getExchangeTradeId() != null && journal.getExchangeTradeId().equals(exchange.tradeId())
            && journal.getExchangeOrderId() != null && journal.getExchangeOrderId().equals(exchange.orderId())
            && sameText(journal.getSymbol(), exchange.symbol())
            && sameText(journal.getSide(), exchange.side())
            && sameDecimal(journal.getQuantity(), exchange.quantity())
            && sameDecimal(journal.getPrice(), exchange.price())
            && sameDecimal(journal.getCommission(), exchange.commission())
            && sameText(journal.getCommissionAsset(), exchange.commissionAsset())
            && sameDecimal(journal.getRealizedPnl(), exchange.realizedPnl())
            && journal.getTradeTime() != null && sameTime(journal.getTradeTime(), utc(exchange.time()));
    }

    private static ExchangeGateway.Credential toExchangeCredential(QuantCredential credential) {
        return new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(),
            credential.getSignatureType(), credential.getRunMode());
    }

    private Instant exchangeNow(QuantCredential credential) {
        return Instant.now().plusMillis(gateway.exchangeTimeOffsetMillis(credential.getRunMode()));
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(TIME_PRECISION);
    }

    private static boolean sameTime(LocalDateTime left, LocalDateTime right) {
        return left != null && right != null
            && left.truncatedTo(TIME_PRECISION).equals(right.truncatedTo(TIME_PRECISION));
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean sameText(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            throw new IllegalStateException("重建数据缺少字段: " + key);
        }
        return value.toString();
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("重建数据缺少数字字段: " + key);
        }
        return number.longValue();
    }

    private static Long nullableLong(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalStateException("重建数据缺少金额字段: " + key);
        }
        return number;
    }

    private static LocalDateTime dateTime(Map<String, Object> row, String key) {
        LocalDateTime value = nullableDateTime(row, key);
        if (value == null) {
            throw new IllegalStateException("重建数据缺少时间字段: " + key);
        }
        return value;
    }

    private static LocalDateTime nullableDateTime(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        throw new IllegalStateException("重建数据时间字段类型错误: " + key);
    }

    public record RebuildProgress(String runId, String status, long processedCount, long sourceFillCount,
                                  String lastTradeTime, Long lastTradeId) {
    }

    private record ExchangePosition(BigDecimal longQuantity, BigDecimal shortQuantity,
                                    BigDecimal longCostBasis, BigDecimal shortCostBasis) {
    }
}
