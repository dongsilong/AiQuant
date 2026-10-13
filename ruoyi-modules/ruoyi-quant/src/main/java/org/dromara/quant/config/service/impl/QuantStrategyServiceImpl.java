package org.dromara.quant.config.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.quant.config.domain.QuantStrategy;
import org.dromara.quant.config.domain.bo.QuantStrategyBo;
import org.dromara.quant.config.domain.vo.QuantStrategyVo;
import org.dromara.quant.config.mapper.QuantStrategyMapper;
import org.dromara.quant.config.service.IQuantStrategyService;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 简单价格监听策略：固定 ETHUSDT / 15m，只读取市场事件，不产生交易信号。
 * 运行状态只代表当前进程中的订阅，不会在重启后自动开启。
 */
@Slf4j
@Service
public class QuantStrategyServiceImpl implements IQuantStrategyService {
    private static final String PLUGIN = "price_logger";
    private static final String SYMBOL = "ETHUSDT";
    private static final String INTERVAL = "15m";

    private final QuantStrategyMapper mapper;
    private final ExchangeGateway gateway;
    private final ScheduledExecutorService scheduler;
    private final Map<Long, ScheduledFuture<?>> subscriptions = new ConcurrentHashMap<>();

    public QuantStrategyServiceImpl(QuantStrategyMapper mapper, ExchangeGateway gateway,
                                    @Qualifier("quantScheduledExecutorService") ScheduledExecutorService scheduler) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.scheduler = scheduler;
    }
    private final Map<Long, Boolean> healthySubscriptions = new ConcurrentHashMap<>();
    private final Map<Long, AtomicLong> successfulPolls = new ConcurrentHashMap<>();

    @Override
    public PageResult<QuantStrategyVo> queryPage(PageQuery query) {
        LambdaQueryWrapper<QuantStrategy> wrapper = new LambdaQueryWrapper<QuantStrategy>()
            .orderByDesc(QuantStrategy::getCreateTime);
        Page<QuantStrategyVo> page = mapper.selectVoPage(query.build(), wrapper);
        page.getRecords().forEach(this::applyStatus);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public QuantStrategyVo get(Long id) {
        QuantStrategyVo vo = mapper.selectVoById(id);
        if (vo == null) throw new ServiceException("策略不存在");
        return applyStatus(vo);
    }

    @Override
    public QuantStrategyVo insert(QuantStrategyBo input) {
        validate(input);
        QuantStrategy entity = MapstructUtils.convert(input, QuantStrategy.class);
        entity.setId(null);
        defaults(entity);
        entity.setCreateTime(LocalDateTime.now());
        mapper.insert(entity);
        return get(entity.getId());
    }

    @Override
    public synchronized QuantStrategyVo update(QuantStrategyBo input) {
        validate(input);
        if (input.getId() == null || mapper.selectById(input.getId()) == null) throw new ServiceException("策略不存在");
        if (subscriptions.containsKey(input.getId())) throw new ServiceException("运行中策略不能编辑，请先停止");
        QuantStrategy entity = MapstructUtils.convert(input, QuantStrategy.class);
        defaults(entity);
        mapper.updateById(entity);
        return get(entity.getId());
    }

    @Override
    public synchronized Boolean delete(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return false;
        for (Long id : ids) {
            if (subscriptions.containsKey(id)) throw new ServiceException("请先停止运行中的策略");
        }
        return mapper.deleteByIds(ids) > 0;
    }

    @Override
    public synchronized boolean start(Long id) {
        log.info("[策略启动请求] strategyId={} symbol={} interval={}", id, SYMBOL, INTERVAL);
        QuantStrategy entity = mapper.selectById(id);
        if (entity == null) throw new ServiceException("策略不存在: " + id);
        if (!PLUGIN.equals(entity.getPluginCode())) throw new ServiceException("当前仅支持价格日志策略");
        if (subscriptions.containsKey(id)) {
            log.info("[策略重复启动] strategyId={} healthy={} ticks={}", id,
                healthySubscriptions.get(id), successfulPolls.getOrDefault(id, new AtomicLong()).get());
            return true;
        }

        // 先执行一次同步读取：只有真正从交易所取到有效 K 线才能返回启动成功。
        // 避免仅创建定时任务却没有数据，前端错误显示运行中。
        log.info("[策略行情预检开始] strategyId={}", id);
        try {
            pollMarket(id, entity);
        } catch (RuntimeException ex) {
            healthySubscriptions.remove(id);
            successfulPolls.remove(id);
            log.error("[策略启动失败] strategyId={} errorType={} reason={}",
                id, ex.getClass().getSimpleName(), ex.getMessage());
            throw new ServiceException("策略启动失败，获取 ETHUSDT 15m 行情异常: " +
                ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        try {
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
                if (!subscriptions.containsKey(id)) return;
                try {
                    pollMarket(id, entity);
                } catch (Throwable ex) {
                    healthySubscriptions.put(id, false);
                    log.error("[策略行情轮询失败] strategyId={} type={} reason={}",
                        id, ex.getClass().getSimpleName(), ex.getMessage());
                }
            }, 5, 5, TimeUnit.SECONDS);
            subscriptions.put(id, future);
        } catch (RuntimeException ex) {
            healthySubscriptions.remove(id);
            successfulPolls.remove(id);
            log.error("[策略任务创建失败] strategyId={} type={} reason={}", id,
                ex.getClass().getSimpleName(), ex.getMessage());
            throw new ServiceException("策略调度任务创建失败: " + ex.getMessage());
        }
        log.info("[策略启动成功] strategyId={} symbol={} interval={} pollSeconds=5 mode=READ_ONLY",
            id, SYMBOL, INTERVAL);
        return true;
    }

    private void pollMarket(Long id, QuantStrategy entity) {
        // 获取一根实时形成中的15分钟K线，另外获取最新成交价；全程只读，绝不调用交易端口。
        log.debug("[策略行情请求] strategyId={} symbol={} interval={}", id, SYMBOL, INTERVAL);
        List<ExchangeGateway.Kline> klines = gateway.queryKlines(SYMBOL, INTERVAL, 1);
        if (klines == null || klines.isEmpty() || klines.getLast() == null
            || klines.getLast().close() == null) {
            throw new IllegalStateException("Binance 15m K线为空");
        }
        ExchangeGateway.Kline candle = klines.getLast();
        ExchangeGateway.PriceSnapshot snapshot = gateway.queryPrice(SYMBOL);
        if (snapshot == null || snapshot.lastTradePrice() == null) {
            throw new IllegalStateException("Binance 最新价格为空");
        }
        successfulPolls.computeIfAbsent(id, unused -> new AtomicLong()).incrementAndGet();
        healthySubscriptions.put(id, true);
        log.info("[策略K线] strategyId={} name={} symbol={} interval={} openTime={} open={} high={} low={} close={} volume={} closed={}",
            id, entity.getStrategyName(), SYMBOL, INTERVAL, candle.openTime(), candle.open(),
            candle.high(), candle.low(), candle.close(), candle.volume(), candle.closed());
        log.info("[策略实时价格] strategyId={} symbol={} price={} observedAt={} totalPolls={}",
            id, SYMBOL, snapshot.lastTradePrice(), snapshot.observedAt(), successfulPolls.get(id).get());
    }

    @Override
    public synchronized boolean stop(Long id) {
        if (mapper.selectById(id) == null) throw new ServiceException("策略不存在");
        ScheduledFuture<?> handle = subscriptions.get(id);
        if (handle != null) {
            handle.cancel(false);
            subscriptions.remove(id);
            healthySubscriptions.remove(id);
            successfulPolls.remove(id);
        }
        log.info("[策略停止] id={}", id);
        return true;
    }

    @PreDestroy
    public synchronized void shutdown() {
        subscriptions.values().forEach(handle -> handle.cancel(false));
        subscriptions.clear();
        healthySubscriptions.clear();
        successfulPolls.clear();
    }

    private QuantStrategyVo applyStatus(QuantStrategyVo vo) {
        vo.setStatus(!subscriptions.containsKey(vo.getId()) ? "STOPPED" :
            Boolean.TRUE.equals(healthySubscriptions.get(vo.getId())) ? "RUNNING" : "CONNECTING");
        return vo;
    }

    private void validate(QuantStrategyBo input) {
        if (input == null || !StringUtils.hasText(input.getStrategyName())) throw new ServiceException("策略名称不能为空");
        if (!PLUGIN.equals(input.getPluginCode())) throw new ServiceException("暂时仅支持 price_logger 策略");
    }

    private void defaults(QuantStrategy entity) {
        entity.setPluginCode(PLUGIN);
        entity.setPluginVersion("1.0");
        entity.setStrategyType("DEMO");
        entity.setDefaultInterval(INTERVAL);
        entity.setParamsJson("{}");
        entity.setStatus("0");
        entity.setMaxOpenCount(0);
        entity.setUpdateTime(LocalDateTime.now());
    }

    @Override
    public void checkAccess(QuantStrategyBo strategy) {
        // 当前仅有 HTTP 接口权限控制，后续应接入策略所有权/用户授权。
    }

    @Override
    public List<Long> accessibleStrategyIds() {
        return List.of();
    }
}
