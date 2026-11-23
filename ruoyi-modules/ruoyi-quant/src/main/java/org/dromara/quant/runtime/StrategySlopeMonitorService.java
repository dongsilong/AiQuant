package org.dromara.quant.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.MarketEventFreshness;
import org.dromara.quant.market.MarketStreamHub;
import org.dromara.quant.market.OneMinuteMovingAverage;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.strategy.KlineSlopeRateStrategy;
import org.dromara.quant.strategy.StrategyContext;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.dromara.quant.util.QuantJsonMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * 交易终端只读斜率监控；复用共享行情流，不依赖策略 Runtime，也不执行任何交易动作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StrategySlopeMonitorService {
    private final StrategySlopeTelemetryHub telemetryHub;
    private final MarketStreamHub marketStreamHub;
    private final QuantStrategySymbolMapper strategySymbolMapper;
    private final QuantSymbolMapper symbolMapper;
    private final QuantProperties properties;
    private final ExchangeGateway gateway;
    private final Map<Key, Session> sessions = new ConcurrentHashMap<>();
    private final Map<MonitorKey, AtomicLong> sequences = new ConcurrentHashMap<>();

    /**
     * 为一个 SSE 客户端加入策略/账户监控会话；同一策略和账户的多个客户端共享行情订阅。
     */
    public AutoCloseable subscribe(QuantStrategy strategy, QuantCredential credential, String fallbackInterval) {
        if (strategy == null || credential == null || !KlineSlopeRateStrategy.CODE.equals(strategy.getPluginCode())) {
            return () -> {
            };
        }
        Key key = new Key(strategy.getId(), credential.getId(), fallbackInterval == null ? "" : fallbackInterval.trim());
        Session session = sessions.compute(key, (ignored, current) -> {
            if (current != null) {
                current.references++;
                return current;
            }
            Session created = new Session();
            startMonitors(created, strategy, credential, fallbackInterval);
            created.references = 1;
            return created;
        });
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (released.compareAndSet(false, true)) {
                sessions.computeIfPresent(key, (ignored, current) -> {
                    if (current != session) {
                        return current;
                    }
                    if (--current.references == 0) {
                        current.close();
                        return null;
                    }
                    return current;
                });
            }
        };
    }

    private void startMonitors(Session session, QuantStrategy strategy, QuantCredential credential, String fallbackInterval) {
        List<QuantStrategySymbol> bindings;
        try {
            bindings = strategySymbolMapper.selectList(new LambdaQueryWrapper<QuantStrategySymbol>()
                .eq(QuantStrategySymbol::getStrategyId, strategy.getId()));
        } catch (RuntimeException ex) {
            log.warn("实时斜率监控读取策略交易对失败 strategyId={}", strategy.getId(), ex);
            return;
        }
        if (bindings == null || bindings.isEmpty()) {
            return;
        }
        for (QuantStrategySymbol binding : bindings) {
            if ("1".equals(binding.getStatus())) {
                continue;
            }
            QuantSymbol symbol = null;
            String interval = binding.getIntervalCode();
            try {
                symbol = binding.getSymbolId() == null ? null : symbolMapper.selectById(binding.getSymbolId());
                if (symbol == null || !"0".equals(symbol.getStatus())) {
                    continue;
                }
                if (interval == null || interval.isBlank()) {
                    interval = fallbackInterval;
                }
                if (interval == null || interval.isBlank()) {
                    interval = strategy.getDefaultInterval();
                }
                if (interval == null || interval.isBlank()) {
                    log.warn("实时斜率监控缺少K线周期 strategyId={} symbol={}", strategy.getId(), symbol.getSymbol());
                    continue;
                }
                interval = interval.trim();
                Map<String, Object> params = new HashMap<>();
                if (strategy.getParamsJson() != null && !strategy.getParamsJson().isBlank()) {
                    params.putAll(QuantJsonMapper.readMap(strategy.getParamsJson()));
                }
                if (binding.getParamsJson() != null && !binding.getParamsJson().isBlank()) {
                    params.putAll(QuantJsonMapper.readMap(binding.getParamsJson()));
                }
                int leverage = binding.getLeverage() != null && binding.getLeverage() > 0
                    ? binding.getLeverage()
                    : strategy.getDefaultLeverage() == null || strategy.getDefaultLeverage() <= 0 ? 1 : strategy.getDefaultLeverage();
                Monitor monitor = new Monitor(strategy.getId(), credential.getId(), credential.getRunMode(),
                    symbol.getSymbol(), interval, leverage, params);
                try {
                    monitor.subscriptions.add(marketStreamHub.subscribe(
                        credential.getRunMode(), symbol.getSymbol(), interval, event -> {
                            try {
                                monitor.onMarketEvent(event);
                            } catch (RuntimeException ex) {
                                monitor.reportFailure(ex);
                            }
                        }, ignored -> {
                        }));
                    if (!"1m".equalsIgnoreCase(interval)) {
                        Predicate<ExchangeGateway.MarketEvent> filter = event -> event != null
                            && ("KLINE".equalsIgnoreCase(event.type()) || "MARK".equalsIgnoreCase(event.type())
                            || "KLINE_READY".equals(event.type()) || "CONNECTED".equals(event.type())
                            || "CONNECTING".equals(event.type()) || "DISCONNECTED".equals(event.type())
                            || "ERROR".equals(event.type()));
                        monitor.subscriptions.add(marketStreamHub.subscribe(
                            credential.getRunMode(), symbol.getSymbol(), "1m", event -> {
                                try {
                                    monitor.onMaMarketEvent(event);
                                } catch (RuntimeException ex) {
                                    monitor.reportFailure(ex);
                                }
                            }, ignored -> {
                            }, filter));
                    }
                    session.monitors.add(monitor);
                } catch (RuntimeException ex) {
                    monitor.close();
                    throw ex;
                }
            } catch (RuntimeException ex) {
                log.warn("实时斜率监控订阅失败 strategyId={} credentialId={} symbol={} interval={}",
                    strategy.getId(), credential.getId(), symbol == null ? binding.getSymbolId() : symbol.getSymbol(), interval, ex);
            }
        }
    }

    @PreDestroy
    public void close() {
        sessions.values().forEach(Session::close);
        sessions.clear();
    }

    private void publish(Monitor monitor) {
        SlopeInput input = monitor.latestSlopeInput;
        Instant now = Instant.now();
        Instant exchangeNow = now.plusMillis(gateway.exchangeTimeOffsetMillis(monitor.runMode));
        long maxAgeMillis = Math.max(1, properties.getMarket().getMaxEventAgeMillis());
        KlineSlopeRateStrategy.SlopeEvaluation evaluation = input == null ? null
            : KlineSlopeRateStrategy.calculateSlope(input.context(), monitor.params);
        long slopeFreshForMillis = input == null ? 0L : remainingFreshnessMillis(
            input.context().eventTime(), input.receivedAt(), now, exchangeNow, maxAgeMillis);
        boolean slopeReady = evaluation != null && slopeFreshForMillis > 0;

        String maBlockReason = maBlockReason(monitor.ma, now, exchangeNow, maxAgeMillis);
        boolean maReady = maBlockReason == null;
        long maFreshForMillis = maReady ? remainingFreshnessMillis(
            monitor.ma.eventTime, monitor.ma.receivedAt, now, exchangeNow, maxAgeMillis) : 0L;
        if (maReady) {
            Instant nextExchangeMinute = exchangeNow.truncatedTo(ChronoUnit.MINUTES).plusSeconds(60L);
            maFreshForMillis = Math.min(maFreshForMillis,
                Math.max(0L, java.time.Duration.between(exchangeNow, nextExchangeMinute).toMillis()));
            maReady = maFreshForMillis > 0;
            if (!maReady) {
                maBlockReason = "STALE_EVENT";
            }
        }

        BigDecimal ma5 = maReady ? monitor.ma.ma5 : null;
        BigDecimal ma7 = maReady ? monitor.ma.ma7 : null;
        MonitorKey monitorKey = new MonitorKey(monitor.strategyId, monitor.credentialId, monitor.symbol, monitor.interval);
        telemetryHub.publish(new StrategySlopeTelemetry(
            monitor.strategyId, null, monitor.credentialId, monitor.symbol, monitor.interval,
            slopeReady ? evaluation.currentPrice() : null,
            slopeReady ? evaluation.referenceOpenPrice() : null,
            slopeReady ? evaluation.slopePercent() : null,
            slopeReady ? evaluation.degrees() : null,
            evaluation == null ? null : evaluation.entryThreshold(),
            slopeReady && evaluation.entryApproved(),
            input == null ? null : input.context().eventTime(),
            input == null ? null : input.receivedAt(),
            sequences.computeIfAbsent(monitorKey, ignored -> new AtomicLong()).incrementAndGet(),
            slopeReady,
            ma5,
            ma7,
            maReady ? OneMinuteMovingAverage.trend(ma5, ma7) : null,
            maReady,
            maBlockReason,
            monitor.ma.market.lastClosedKlineOpenTime,
            monitor.ma.eventTime,
            monitor.ma.receivedAt,
            slopeFreshForMillis,
            maFreshForMillis,
            now
        ));
    }

    private String maBlockReason(MaState ma, Instant now, Instant exchangeNow, long maxAgeMillis) {
        if (!ma.historyReady || !ma.streamReady || ma.eventTime == null || ma.receivedAt == null) {
            return "STREAM_NOT_READY";
        }
        if (ma.ma5 == null || ma.ma7 == null) {
            return "INSUFFICIENT_CLOSED_KLINES";
        }
        if (!MarketEventFreshness.isFresh(ma.eventTime, ma.receivedAt, now, exchangeNow, maxAgeMillis)) {
            return "STALE_EVENT";
        }
        Instant lastClosedKlineOpenTime = ma.market.lastClosedKlineOpenTime;
        if (lastClosedKlineOpenTime == null) {
            return "LATEST_CLOSED_KLINE_MISSING";
        }
        Instant currentMinuteOpen = exchangeNow.truncatedTo(ChronoUnit.MINUTES);
        Instant expectedLastClosedKlineOpenTime = currentMinuteOpen.minusSeconds(60L);
        if (!lastClosedKlineOpenTime.equals(expectedLastClosedKlineOpenTime)) {
            Instant currentMinuteCloseTime = currentMinuteOpen.plusSeconds(60L).minusMillis(1L);
            boolean justClosedCurrentMinute = lastClosedKlineOpenTime.equals(currentMinuteOpen)
                && !exchangeNow.isBefore(currentMinuteCloseTime);
            if (!justClosedCurrentMinute) {
                return "LATEST_CLOSED_KLINE_STALE";
            }
        }
        return null;
    }

    private static long remainingFreshnessMillis(Instant eventTime, Instant receivedAt, Instant localNow,
                                                   Instant exchangeNow, long maxAgeMillis) {
        if (!MarketEventFreshness.isFresh(eventTime, receivedAt, localNow, exchangeNow, maxAgeMillis)) {
            return 0L;
        }
        long eventAgeMillis = java.time.Duration.between(eventTime, exchangeNow).toMillis();
        long receivedAgeMillis = java.time.Duration.between(receivedAt, localNow).toMillis();
        return Math.max(0L, Math.min(maxAgeMillis - eventAgeMillis, maxAgeMillis - receivedAgeMillis));
    }

    private final class Monitor {
        private final Long strategyId;
        private final Long credentialId;
        private final String runMode;
        private final String symbol;
        private final String interval;
        private final int leverage;
        private final Map<String, Object> params;
        private final MarketState market = new MarketState();
        private final MaState ma = new MaState();
        private final List<AutoCloseable> subscriptions = new ArrayList<>();
        private volatile boolean closed;
        private BigDecimal latestTradePrice;
        private Instant latestTradeEventAt;
        private SlopeInput latestSlopeInput;
        private volatile long nextErrorLogAt;

        private Monitor(Long strategyId, Long credentialId, String runMode, String symbol,
                        String interval, int leverage, Map<String, Object> params) {
            this.strategyId = strategyId;
            this.credentialId = credentialId;
            this.runMode = runMode;
            this.symbol = symbol;
            this.interval = interval;
            this.leverage = leverage;
            this.params = params;
        }

        private synchronized void onMarketEvent(ExchangeGateway.MarketEvent event) {
            if (closed || event == null) {
                return;
            }
            if ("1m".equalsIgnoreCase(interval)) {
                onMaMarketEvent(event);
            }
            if (event.kline() != null) {
                market.updateKline(event.kline());
                market.lastKlineEventAt = event.eventTime();
            }
            if ("TRADE".equalsIgnoreCase(event.type()) && event.price() != null && event.price().signum() > 0) {
                latestTradePrice = event.price();
                latestTradeEventAt = event.eventTime();
            }
            if ("CONNECTING".equals(event.type()) || "DISCONNECTED".equals(event.type()) || "ERROR".equals(event.type())) {
                latestTradePrice = null;
                latestTradeEventAt = null;
                latestSlopeInput = null;
                publish(Monitor.this);
                return;
            }
            if (event.replay() || (!"TRADE".equalsIgnoreCase(event.type()) && !"KLINE".equalsIgnoreCase(event.type()))) {
                return;
            }
            Instant now = Instant.now();
            Instant exchangeNow = now.plusMillis(gateway.exchangeTimeOffsetMillis(runMode));
            long maxAgeMillis = Math.max(1, properties.getMarket().getMaxEventAgeMillis());
            if (!MarketEventFreshness.isFresh(event.eventTime(), event.receivedAt(), now, exchangeNow, maxAgeMillis)) {
                return;
            }
            BigDecimal price = currentPrice(now, maxAgeMillis);
            if (price == null || price.signum() <= 0 || market.previousKlineOpenPrice() == null) {
                return;
            }
            StrategyContext context = new StrategyContext(
                symbol, null, null, price, market.referencePrice(), market.previousKlineOpenPrice(),
                market.currentKlineOpen(), market.currentKlineClosed(), event.eventTime(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, leverage, null, null, false, 0, null
            );
            latestSlopeInput = new SlopeInput(context, event.receivedAt());
            publish(Monitor.this);
        }

        private synchronized void onMaMarketEvent(ExchangeGateway.MarketEvent event) {
            if (closed || event == null) {
                return;
            }
            if (event.kline() != null) {
                ma.market.updateKline(event.kline());
            }
            if ("CONNECTING".equals(event.type()) || "DISCONNECTED".equals(event.type()) || "ERROR".equals(event.type())) {
                ma.markUnready();
                if (!event.replay()) {
                    publish(Monitor.this);
                }
                return;
            }
            if ("KLINE_READY".equals(event.type())) {
                ma.historyReady = true;
                publish(Monitor.this);
                return;
            }
            if (event.kline() != null || "MARK".equalsIgnoreCase(event.type())) {
                ma.ma5 = event.maReady() ? event.ma5() : null;
                ma.ma7 = event.maReady() ? event.ma7() : null;
            }
            if (!event.replay() && "CONNECTED".equals(event.type())) {
                ma.streamReady = true;
            }
            if (!event.replay() && ("KLINE".equalsIgnoreCase(event.type()) || "MARK".equalsIgnoreCase(event.type())
                || "CONNECTED".equals(event.type())) && event.eventTime() != null && event.receivedAt() != null) {
                ma.eventTime = event.eventTime();
                ma.receivedAt = event.receivedAt();
            }
            if (!event.replay() && (event.kline() != null || "MARK".equalsIgnoreCase(event.type()) || "CONNECTED".equals(event.type()))) {
                publish(Monitor.this);
            }
        }

        private void reportFailure(RuntimeException ex) {
            long now = System.currentTimeMillis();
            if (now >= nextErrorLogAt) {
                nextErrorLogAt = now + 10_000L;
                log.warn("实时斜率只读监控事件处理失败 strategyId={} credentialId={} symbol={} interval={}",
                    strategyId, credentialId, symbol, interval, ex);
            }
        }

        private BigDecimal currentPrice(Instant now, long maxAgeMillis) {
            Instant deadline = now.minusMillis(Math.max(1, maxAgeMillis));
            if (latestTradePrice != null && latestTradeEventAt != null && !latestTradeEventAt.isBefore(deadline)
                && (market.lastKlineEventAt == null || !latestTradeEventAt.isBefore(market.lastKlineEventAt))) {
                return latestTradePrice;
            }
            return market.currentKline == null ? null : market.currentKline.close();
        }

        private void close() {
            closed = true;
            for (AutoCloseable subscription : subscriptions) {
                try {
                    subscription.close();
                } catch (Exception ignored) {
                }
            }
            subscriptions.clear();
        }
    }

    private static final class MarketState {
        private ExchangeGateway.Kline currentKline;
        private BigDecimal previousClosedClose;
        private BigDecimal previousKlineOpen;
        private BigDecimal secondPreviousKlineOpen;
        private BigDecimal thirdPreviousKlineOpen;
        private Instant lastKlineEventAt;
        private Instant lastClosedKlineOpenTime;

        private void updateKline(ExchangeGateway.Kline kline) {
            if (kline == null || kline.openTime() == null) {
                return;
            }
            if (currentKline != null && kline.openTime().isBefore(currentKline.openTime())) {
                return;
            }
            if (kline.closed() && kline.close() != null && kline.close().signum() > 0
                && (lastClosedKlineOpenTime == null || kline.openTime().isAfter(lastClosedKlineOpenTime))) {
                lastClosedKlineOpenTime = kline.openTime();
            }
            if (currentKline != null && !currentKline.openTime().equals(kline.openTime())) {
                thirdPreviousKlineOpen = secondPreviousKlineOpen;
                secondPreviousKlineOpen = previousKlineOpen;
                previousKlineOpen = currentKline.open();
                if (currentKline.closed()) {
                    previousClosedClose = currentKline.close();
                }
            }
            currentKline = kline;
            if (kline.closed()) {
                previousClosedClose = kline.close();
            }
        }

        private BigDecimal referencePrice() {
            return previousClosedClose;
        }

        private BigDecimal previousKlineOpenPrice() {
            return switch (KlineSlopeRateStrategy.SLOPE_LOOKBACK_BARS) {
                case 2 -> secondPreviousKlineOpen;
                case 3 -> thirdPreviousKlineOpen;
                default -> previousKlineOpen;
            };
        }

        private Instant currentKlineOpen() {
            return currentKline == null ? null : currentKline.openTime();
        }

        private boolean currentKlineClosed() {
            return currentKline != null && currentKline.closed();
        }
    }

    private static final class MaState {
        private final MarketState market = new MarketState();
        private boolean historyReady;
        private boolean streamReady;
        private BigDecimal ma5;
        private BigDecimal ma7;
        private Instant eventTime;
        private Instant receivedAt;

        private void markUnready() {
            historyReady = false;
            streamReady = false;
            ma5 = null;
            ma7 = null;
            eventTime = null;
            receivedAt = null;
        }
    }

    private static final class Session {
        private final List<Monitor> monitors = new ArrayList<>();
        private int references;

        private void close() {
            monitors.forEach(Monitor::close);
            monitors.clear();
        }
    }

    private record Key(Long strategyId, Long credentialId, String fallbackInterval) {
    }

    private record MonitorKey(Long strategyId, Long credentialId, String symbol, String interval) {
    }

    private record SlopeInput(StrategyContext context, Instant receivedAt) {
    }
}
