package org.dromara.quant.runtime.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.position.domain.QuantPosition;
import org.dromara.quant.position.mapper.QuantPositionMapper;
import org.dromara.quant.protection.ProtectionOrderService;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.dromara.quant.runtime.domain.QuantStrategyPositionState;
import org.dromara.quant.strategy.KlineSlopeRateStrategy;
import org.dromara.quant.strategy.ProtectionSignal;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双开腿成交恢复后的 Binance 保护委托安装器。
 *
 * <p>仅对订单、成交和交易所持仓三方事实均已核实的腿创建保护。
 * 保护单按本地 QuantPosition ID 和持仓周期键隔离，避免 LONG/SHORT 或历史周期串单。</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DualHedgeProtectionLifecycleService {
    private static final BigDecimal HARD_STOP_ROI = new BigDecimal("0.03");
    private static final BigDecimal FIRST_TAKE_PROFIT_ROI = new BigDecimal("0.05");
    private static final BigDecimal FIRST_TAKE_PROFIT_QUANTITY_RATE = new BigDecimal("0.50");
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long PROTECTION_RECHECK_MILLIS = 30_000L;

    private final StrategyHedgeRoundService roundService;
    private final DualHedgeOrderFactRecoveryService recoveryService;
    private final StrategyPositionStateService positionStateService;
    private final QuantPositionMapper positionMapper;
    private final QuantCredentialMapper credentialMapper;
    private final TradeExecutionApplicationService executionService;
    private final ProtectionOrderService protectionOrderService;
    private final MarketStreamHub marketStreamHub;

    private final ConcurrentHashMap<String, Long> protectionCheckAt = new ConcurrentHashMap<>();

    /**
     * 先恢复未完成的订单轮次，再为已核实的各条腿建立独立保护。
     */
    @Scheduled(fixedDelay = 5000L, initialDelay = 5000L)
    public void reconcile() {
        for (QuantStrategyHedgeRound round : roundService.findPendingRecoveryRounds()) {
            QuantCredential credential = credentialMapper.selectById(round.getCredentialId());
            if (credential == null || !"0".equals(credential.getStatus())
                || credential.getCredentialRuntimeVersion() == null
                || "PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                recoveryService.inspect(round, executionService.toCredential(credential));
            } catch (RuntimeException ex) {
                log.warn("双开轮次成交事实恢复暂未完成 hedgeRoundKey={} symbol={} errorType={}",
                    round.getHedgeRoundKey(), round.getSymbol(), ex.getClass().getSimpleName());
            }
        }

        for (QuantStrategyPositionState state : positionStateService.findUnclosed()) {
            if (!isOpenDualLeg(state)) {
                continue;
            }
            QuantPosition position = positionMapper.selectById(state.getPositionId());
            if (!matchesRecoveredPosition(state, position)) {
                continue;
            }
            String checkKey = state.getPositionCycleKey() + ":" + position.getQuantity().stripTrailingZeros().toPlainString()
                + ":" + Boolean.TRUE.equals(state.getFirstTakeProfitTriggered());
            long now = System.currentTimeMillis();
            Long checkedAt = protectionCheckAt.get(checkKey);
            if (Boolean.TRUE.equals(state.getExchangeProtectionReady())
                && checkedAt != null && now - checkedAt < PROTECTION_RECHECK_MILLIS) {
                continue;
            }
            try {
                ensureExchangeProtections(state, position);
                state.setExchangeProtectionReady(true);
                if (state.getPendingAction() == null) {
                    state.setRecoveryRequired(false);
                    state.setUnsafeRecovery(false);
                }
                positionStateService.save(state);
                protectionCheckAt.put(checkKey, now);
            } catch (RuntimeException ex) {
                protectionCheckAt.remove(checkKey);
                if (Boolean.TRUE.equals(state.getExchangeProtectionReady())) {
                    state.setExchangeProtectionReady(false);
                    positionStateService.save(state);
                }
                log.error("双开腿交易所保护尚未全部确认，保留本地恢复阻断 instanceId={} leg={} positionId={} symbol={} errorType={}",
                    state.getInstanceId(), state.getLegKey(), state.getPositionId(), position.getSymbol(),
                    ex.getClass().getSimpleName());
            }
        }
    }

    void ensureExchangeProtections(QuantStrategyPositionState state, QuantPosition position) {
        if (!isOpenDualLeg(state) || !matchesRecoveredPosition(state, position)
            || state.getEntryPrice() == null || state.getEntryPrice().signum() <= 0
            || state.getInitialPositionQty() == null || state.getInitialPositionQty().signum() <= 0
            || state.getInitialMargin() == null || state.getInitialMargin().signum() <= 0
            || state.getFrozenLeverage() == null || state.getFrozenLeverage() <= 0) {
            throw new IllegalStateException("双开腿缺少创建保护所需的冻结成交和保证金事实");
        }

        int leverage = state.getFrozenLeverage();
        BigDecimal hardStopPrice = KlineSlopeRateStrategy.priceForMarkReturn(
            state.getEntryPrice(), state.getDirection(), HARD_STOP_ROI.negate(), leverage);
        BigDecimal firstTakeProfitPrice = KlineSlopeRateStrategy.priceForMarkReturn(
            state.getEntryPrice(), state.getDirection(), FIRST_TAKE_PROFIT_ROI, leverage);
        BigDecimal breakEvenPrice = KlineSlopeRateStrategy.priceForMarkReturn(
            state.getEntryPrice(), state.getDirection(), ZERO, leverage);

        position.setProtectionCycleKey(state.getPositionCycleKey());
        position.setProtectionPolicyVersion(3);
        position.setProtectionStopLossPrice(hardStopPrice);

        QuantCredential credential = executionService.requireCredential(position.getCredentialId());
        boolean live = !"PAPER".equalsIgnoreCase(credential.getRunMode());
        var hardStop = protectionOrderService.ensureMarginHardStop(
            position.getCredentialId(), position, HARD_STOP_ROI, live);
        if (hardStop == null) {
            throw new IllegalStateException("双开腿 -3% 整仓硬止损尚未创建或确认");
        }

        List<ProtectionSignal> desired = new java.util.ArrayList<>();
        if (!Boolean.TRUE.equals(state.getFirstTakeProfitTriggered())) {
            desired.add(new ProtectionSignal("FIRST_TP", "TAKE_PROFIT_MARKET",
                FIRST_TAKE_PROFIT_QUANTITY_RATE, ProtectionSignal.QuantityBase.INITIAL,
                firstTakeProfitPrice, "双开策略 +5% 首次止盈，平初始仓位 50%"));
        }
        BigDecimal maximumRoi = state.getMaximumReturnRate() == null ? ZERO : state.getMaximumReturnRate();
        int stepIndex = maximumRoi.signum() <= 0 ? 0
            : maximumRoi.divide(new BigDecimal("0.10"), 0, java.math.RoundingMode.FLOOR).intValue();
        BigDecimal lockRoi = stepIndex == 0 ? ZERO
            : stepIndex == 1 ? new BigDecimal("0.05")
            : new BigDecimal("0.10").multiply(BigDecimal.valueOf(stepIndex - 1L));
        boolean breakEvenArmed = Boolean.TRUE.equals(state.getTrailingArmed()) || stepIndex > 0;
        if (!breakEvenArmed) {
            MarketStreamHub.ProtectionPriceSnapshot price = marketStreamHub.protectionPriceAt(
                credential.getRunMode(), position.getSymbol(), Instant.now());
            BigDecimal markPrice = price == null ? null : price.markPrice();
            breakEvenArmed = markPrice != null && markPrice.signum() > 0
                && ("LONG".equalsIgnoreCase(state.getDirection())
                    ? markPrice.compareTo(state.getEntryPrice()) > 0
                    : markPrice.compareTo(state.getEntryPrice()) < 0);
        }
        if (breakEvenArmed) {
            BigDecimal stepPrice = stepIndex == 0 ? breakEvenPrice
                : KlineSlopeRateStrategy.priceForMarkReturn(state.getEntryPrice(), state.getDirection(), lockRoi, leverage);
            desired.add(new ProtectionSignal("STEP_TP", "STOP_MARKET", BigDecimal.ONE,
                ProtectionSignal.QuantityBase.CURRENT, stepPrice,
                "双开策略阶梯回撤保护，最高 ROI=" + maximumRoi + "，锁定 ROI=" + lockRoi, stepIndex));
            state.setTrailingArmed(true);
            state.setHighestStep(Math.max(state.getHighestStep() == null ? 0 : state.getHighestStep(), stepIndex));
            state.setTrailingStopRoi(lockRoi);
        }

        protectionOrderService.syncStrategyProtections(position.getCredentialId(), position,
            state.getInitialPositionQty(), desired, null,
            state.getFirstTakeProfitLocalExecutedQty() == null ? ZERO : state.getFirstTakeProfitLocalExecutedQty(),
            live);
    }

    private static boolean isOpenDualLeg(QuantStrategyPositionState state) {
        return state != null && state.getInstanceId() != null
            && Set.of("LONG", "SHORT").contains(state.getLegKey())
            && "OPEN".equalsIgnoreCase(state.getStatus())
            && state.getPositionId() != null && state.getPositionCycleKey() != null
            && state.getHedgeRoundKey() != null
            && state.getPositionQty() != null && state.getPositionQty().signum() > 0;
    }

    private static boolean matchesRecoveredPosition(QuantStrategyPositionState state, QuantPosition position) {
        return position != null && state.getPositionId() != null
            && state.getPositionId().equals(position.getId())
            && state.getPositionCycleKey() != null
            && position.getId() != null
            && position.getCredentialId() != null
            && position.getCredentialId() > 0
            && state.getDirection() != null
            && state.getDirection().equalsIgnoreCase(position.getPositionSide())
            && state.getPositionQty() != null && position.getQuantity() != null
            && state.getPositionQty().compareTo(position.getQuantity()) == 0
            && state.getEntryPrice() != null && position.getEntryPrice() != null
            && state.getEntryPrice().compareTo(position.getEntryPrice()) == 0;
    }
}
