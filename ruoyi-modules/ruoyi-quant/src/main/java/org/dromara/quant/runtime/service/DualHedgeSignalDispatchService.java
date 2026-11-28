package org.dromara.quant.runtime.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.execution.TradeExecutionApplicationService;
import org.dromara.quant.execution.TradingPreparationService;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.runtime.coordination.StrategyRuntimeLeaseService;
import org.dromara.quant.runtime.domain.QuantStrategyHedgeRound;
import org.dromara.quant.strategy.DualHedgeSlopeSignalEvaluator;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyMapper;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * 从持久化信号接管双开订单派发；各节点通过策略运行租约和轮次乐观锁竞争唯一执行权。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DualHedgeSignalDispatchService {
    private static final String MARGIN_MODE = "ISOLATED";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final StrategyHedgeRoundService roundService;
    private final StrategyHedgeRoundExecutionService executionService;
    private final TradeExecutionApplicationService tradeExecutionService;
    private final TradingPreparationService preparationService;
    private final StrategyRuntimeLeaseService leaseService;
    private final QuantStrategyInstanceMapper instanceMapper;
    private final QuantStrategyMapper strategyMapper;
    private final QuantStrategySymbolMapper strategySymbolMapper;
    private final QuantCredentialMapper credentialMapper;
    private final QuantSymbolMapper symbolMapper;
    private final QuantProperties properties;

    /**
     * 扫描持久化的待执行信号；节点故障后可由仍持有运行租约的节点继续接管。
     */
    @Scheduled(fixedDelay = 1000, initialDelay = 1000)
    public void dispatchReadySignals() {
        for (QuantStrategyHedgeRound round : roundService.findSignalReady()) {
            try {
                dispatchSignal(round);
            } catch (RuntimeException ex) {
                log.warn("双开信号自动派发暂未完成 hedgeRoundKey={} instanceId={} symbol={}",
                    round.getHedgeRoundKey(), round.getInstanceId(), round.getSymbol(), ex);
            }
        }
    }

    void dispatchSignal(QuantStrategyHedgeRound round) {
        if (round == null || !StrategyHedgeRoundService.STATUS_SIGNAL_READY.equals(round.getStatus())) {
            return;
        }
        if (round.getSignalEventTime() == null) {
            roundService.abortSignalReady(round);
            return;
        }
        Instant signalTime = round.getSignalEventTime().atZone(BUSINESS_ZONE).toInstant();
        long signalAgeMillis = Duration.between(signalTime, Instant.now()).toMillis();
        if (signalAgeMillis > properties.getMarket().getMaxEventAgeMillis()) {
            roundService.abortSignalReady(round);
            log.warn("双开信号已超过允许的行情新鲜度，放弃追价派发 hedgeRoundKey={} signalAgeMillis={}",
                round.getHedgeRoundKey(), signalAgeMillis);
            return;
        }

        QuantStrategyInstance instance = instanceMapper.selectById(round.getInstanceId());
        QuantStrategy strategy = strategyMapper.selectById(round.getStrategyId());
        QuantStrategySymbol binding = instance == null ? null : strategySymbolMapper.selectById(instance.getStrategySymbolId());
        QuantSymbol symbol = binding == null ? null : symbolMapper.selectById(binding.getSymbolId());
        QuantCredential credential = credentialMapper.selectById(round.getCredentialId());
        if (instance == null || !"RUNNING".equals(instance.getStatus())
            || !round.getInstanceId().equals(instance.getId())
            || !round.getStrategyId().equals(instance.getStrategyId())
            || !round.getCredentialId().equals(instance.getCredentialId())
            || strategy == null || !"1".equals(strategy.getStatus())
            || !DualHedgeSlopeSignalEvaluator.CODE.equals(strategy.getPluginCode())
            || binding == null || !round.getStrategyId().equals(binding.getStrategyId())
            || !"0".equals(binding.getStatus()) || Boolean.FALSE.equals(binding.getRuntimeEnabled())
            || !"15m".equalsIgnoreCase(binding.getIntervalCode())
            || symbol == null || !"0".equals(symbol.getStatus()) || symbol.getSymbol() == null
            || !round.getSymbol().equalsIgnoreCase(symbol.getSymbol())
            || credential == null || !"0".equals(credential.getStatus())
            || credential.getCredentialRuntimeVersion() == null) {
            roundService.abortSignalReady(round);
            log.warn("双开信号对应的实例、账户或交易对配置已失效，取消未派发信号 hedgeRoundKey={} instanceId={} credentialId={}",
                round.getHedgeRoundKey(), round.getInstanceId(), round.getCredentialId());
            return;
        }

        String configuredMarginMode = binding.getMarginMode() == null
            ? strategy.getDefaultMarginMode() : binding.getMarginMode();
        if (configuredMarginMode != null && !MARGIN_MODE.equalsIgnoreCase(configuredMarginMode)) {
            roundService.abortSignalReady(round);
            log.warn("双开策略要求逐仓模式，配置不一致，取消未派发信号 hedgeRoundKey={} configuredMarginMode={}",
                round.getHedgeRoundKey(), configuredMarginMode);
            return;
        }

        Integer configuredLeverage = binding.getLeverage() == null
            ? strategy.getDefaultLeverage() : binding.getLeverage();
        if (configuredLeverage == null || !configuredLeverage.equals(round.getLeverage())) {
            roundService.abortSignalReady(round);
            log.warn("双开信号的杠杆配置已变化，取消旧信号 hedgeRoundKey={} signalLeverage={} configuredLeverage={}",
                round.getHedgeRoundKey(), round.getLeverage(), configuredLeverage);
            return;
        }

        try {
            leaseService.requireCurrentOwner(round.getInstanceId(), round.getCredentialId(),
                round.getSymbol(), credential.getCredentialRuntimeVersion());
        } catch (RuntimeException ex) {
            log.debug("当前节点不持有双开策略运行租约，跳过信号派发 hedgeRoundKey={}", round.getHedgeRoundKey());
            return;
        }

        if (!"PAPER".equalsIgnoreCase(credential.getRunMode())) {
            tradeExecutionService.prepareOpenForRuntime(round.getCredentialId(), round.getSymbol(),
                round.getLeverage(), MARGIN_MODE);
            TradingPreparationService.Prepared prepared;
            try {
                prepared = preparationService.requireReady(round.getCredentialId(), round.getSymbol(),
                    round.getLeverage(), MARGIN_MODE);
            } catch (RuntimeException ex) {
                log.debug("双开信号等待逐仓、杠杆或交易规则准备完成 hedgeRoundKey={} reason={}",
                    round.getHedgeRoundKey(), ex.getClass().getSimpleName());
                return;
            }
            if (prepared.positionMode() == null) {
                return;
            }
            if (!prepared.positionMode().hedgeMode()) {
                roundService.abortSignalReady(round);
                log.error("双开策略要求 Binance HEDGE 双向持仓模式，账户当前不是双向模式，信号已取消 hedgeRoundKey={} credentialId={} symbol={}",
                    round.getHedgeRoundKey(), round.getCredentialId(), round.getSymbol());
                return;
            }
        }

        if (!roundService.tryBeginOpening(round)) {
            return;
        }
        Instant eventTime = round.getSignalEventTime().atZone(BUSINESS_ZONE).toInstant();
        StrategyHedgeRoundExecutionService.DispatchResult result = executionService.dispatchOpening(
            round, round.getReferencePrice(), eventTime, credential.getCredentialRuntimeVersion());
        log.info("双开信号自动派发完成 hedgeRoundKey={} symbol={} status={} message={}",
            round.getHedgeRoundKey(), round.getSymbol(), result.status(), result.message());
    }
}
