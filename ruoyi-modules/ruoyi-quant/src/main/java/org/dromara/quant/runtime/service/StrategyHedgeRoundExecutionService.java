package org.dromara.quant.runtime.service;

import lombok.RequiredArgsConstructor;
import org.dromara.quant.execution.ExecutionResult;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.TradingPreparationService;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 双开轮次的顺序派发协调器。
 *
 * <p>这里只负责两笔入场订单的持久化派发事实，不接管持仓成交、退出和保护单。
 * 任一派发结果不确定时立即停止后续派发，交由恢复流程核实。
 */
@RequiredArgsConstructor
@Service
public class StrategyHedgeRoundExecutionService {
    private static final String MARGIN_MODE = "ISOLATED";
    private static final int QUANTITY_SCALE = 16;

    private final StrategyHedgeRoundService roundService;
    private final TradeExecutionApplicationService executionService;
    private final TradingPreparationService preparationService;

    public DispatchResult dispatchOpening(QuantStrategyHedgeRound round, BigDecimal referencePrice,
                                          Instant eventTime, Long credentialRuntimeVersion) {
        requireDispatchable(round, referencePrice, eventTime);

        TradingPreparationService.Prepared prepared;
        try {
            prepared = preparationService.requireReady(round.getCredentialId(), round.getSymbol(),
                round.getLeverage(), MARGIN_MODE);
        } catch (RuntimeException ex) {
            abortBeforeDispatch(round);
            return new DispatchResult("ABORTED", "交易准备未就绪，未发送双开订单");
        }
        if (prepared == null || prepared.positionMode() == null || !prepared.positionMode().hedgeMode()) {
            abortBeforeDispatch(round);
            return new DispatchResult("ABORTED", "账户未处于 HEDGE 双向持仓模式");
        }

        BigDecimal quantity = round.getTargetNotionalPerLeg().divide(referencePrice, QUANTITY_SCALE, RoundingMode.DOWN);
        if (quantity.signum() <= 0) {
            abortBeforeDispatch(round);
            return new DispatchResult("ABORTED", "按参考价格计算的开仓数量无效");
        }

        if (round.getLongIdempotencyKey() == null || round.getLongIdempotencyKey().isBlank()) {
            round.setLongIdempotencyKey(round.getHedgeRoundKey() + ":LONG:OPEN");
        }
        if (round.getShortIdempotencyKey() == null || round.getShortIdempotencyKey().isBlank()) {
            round.setShortIdempotencyKey(round.getHedgeRoundKey() + ":SHORT:OPEN");
        }

        round.setLongLegStatus(StrategyHedgeRoundService.LEG_DISPATCHING);
        roundService.save(round);
        DispatchResult longResult = dispatchLeg(round, "LONG", "BUY", quantity, referencePrice, eventTime, credentialRuntimeVersion);
        if (!"ACCEPTED".equals(longResult.status())) {
            return longResult;
        }

        round.setShortLegStatus(StrategyHedgeRoundService.LEG_DISPATCHING);
        roundService.save(round);
        DispatchResult shortResult = dispatchLeg(round, "SHORT", "SELL", quantity, referencePrice, eventTime, credentialRuntimeVersion);
        if (!"ACCEPTED".equals(shortResult.status())) {
            return shortResult;
        }

        return new DispatchResult("BOTH_ACCEPTED_PENDING_FILL", "LONG/SHORT 开仓订单均已被执行层接受，等待成交事实核实");
    }

    private DispatchResult dispatchLeg(QuantStrategyHedgeRound round, String leg, String side, BigDecimal quantity,
                                       BigDecimal referencePrice, Instant eventTime, Long credentialRuntimeVersion) {
        String key = "LONG".equals(leg) ? round.getLongIdempotencyKey() : round.getShortIdempotencyKey();
        ExecutionResult result;
        try {
            result = executionService.openByRuntime(round.getCredentialId(), round.getStrategyId(),
                round.getInstanceId(), round.getSymbol(), side, leg, quantity, referencePrice, round.getLeverage(),
                MARGIN_MODE, key, eventTime, credentialRuntimeVersion);
        } catch (RuntimeException ex) {
            markLeg(round, leg, StrategyHedgeRoundService.LEG_UNKNOWN, null);
            round.setStatus(StrategyHedgeRoundService.STATUS_DEGRADED);
            roundService.save(round);
            return new DispatchResult(StrategyHedgeRoundService.STATUS_DEGRADED,
                "开仓结果未知，已停止另一腿派发并等待订单恢复核实");
        }
        if (result == null || !result.accepted() || result.orderId() == null) {
            markLeg(round, leg, result != null && !result.accepted()
                ? StrategyHedgeRoundService.LEG_FAILED : StrategyHedgeRoundService.LEG_UNKNOWN, result);
            boolean firstLeg = "LONG".equals(leg);
            round.setStatus(firstLeg && result != null && !result.accepted()
                ? StrategyHedgeRoundService.STATUS_ABORTED : StrategyHedgeRoundService.STATUS_DEGRADED);
            if (firstLeg && result != null && !result.accepted()) {
                round.setShortLegStatus(StrategyHedgeRoundService.LEG_FAILED);
            }
            roundService.save(round);
            return new DispatchResult(round.getStatus(), result == null ? "开仓执行结果缺失，保持待核查"
                : result.accepted() ? "执行层未返回可信订单号，保持待核查" : "开仓订单未被执行层接受，停止本轮后续派发");
        }

        markLeg(round, leg, StrategyHedgeRoundService.LEG_ACCEPTED, result);
        roundService.save(round);
        return new DispatchResult("ACCEPTED", "订单已接受，等待成交事实核实");
    }

    private static void markLeg(QuantStrategyHedgeRound round, String leg, String status, ExecutionResult result) {
        if ("LONG".equals(leg)) {
            round.setLongLegStatus(status);
            if (result != null) {
                round.setLongOrderId(result.orderId());
                round.setLongClientOrderId(result.clientOrderId());
            }
        } else {
            round.setShortLegStatus(status);
            if (result != null) {
                round.setShortOrderId(result.orderId());
                round.setShortClientOrderId(result.clientOrderId());
            }
        }
    }

    private void abortBeforeDispatch(QuantStrategyHedgeRound round) {
        round.setStatus(StrategyHedgeRoundService.STATUS_ABORTED);
        round.setLongLegStatus(StrategyHedgeRoundService.LEG_FAILED);
        round.setShortLegStatus(StrategyHedgeRoundService.LEG_FAILED);
        roundService.save(round);
    }

    private static void requireDispatchable(QuantStrategyHedgeRound round, BigDecimal referencePrice, Instant eventTime) {
        if (round == null || round.getId() == null || round.getVersion() == null || round.getHedgeRoundKey() == null
            || round.getStrategyId() == null || round.getInstanceId() == null || round.getCredentialId() == null
            || round.getSymbol() == null || round.getSymbol().isBlank() || round.getLeverage() == null || round.getLeverage() <= 0
            || round.getTargetNotionalPerLeg() == null || round.getTargetNotionalPerLeg().signum() <= 0
            || referencePrice == null || referencePrice.signum() <= 0 || eventTime == null
            || !StrategyHedgeRoundService.STATUS_OPENING.equals(round.getStatus())
            || !StrategyHedgeRoundService.LEG_NOT_STARTED.equals(round.getLongLegStatus())
            || !StrategyHedgeRoundService.LEG_NOT_STARTED.equals(round.getShortLegStatus())) {
            throw new IllegalArgumentException("双开轮次不满足首次派发条件，拒绝重复或不完整派发");
        }
    }

    public record DispatchResult(String status, String message) {
    }
}
