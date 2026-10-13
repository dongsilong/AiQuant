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
        QuantStrategy entity = mapper.selectById(id);
        if (entity == null) throw new ServiceException("策略不存在");
        if (!PLUGIN.equals(entity.getPluginCode())) throw new ServiceException("当前仅支持价格日志策略");
        if (subscriptions.containsKey(id)) return true;
        healthySubscriptions.put(id, false);
        // 演示策略只读价格与K线，不依赖交易执行所需的严格事件恢复门控。
        // 每5秒轮询一次公开 REST 行情，网络异常时保留任务并在下一轮重试。
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            if (!subscriptions.containsKey(id)) return;
            try {
                ExchangeGateway.PriceSnapshot price = gateway.queryPrice(SYMBOL);
                List<ExchangeGateway.Kline> candles = gateway.queryKlines(SYMBOL, INTERVAL, 1);
                if (price == null || price.lastTradePrice() == null || candles == null || candles.isEmpty()) {
                    healthySubscriptions.put(id, false);
                    log.warn("[策略行情不可用] id={} symbol={} interval={} 公开行情为空", id, SYMBOL, INTERVAL);
                    return;
                }
                ExchangeGateway.Kline candle = candles.getLast();
                healthySubscriptions.put(id, true);
                log.info("[策略实时价格] id={} name={} symbol={} interval={} price={} time={}",
                    id, entity.getStrategyName(), SYMBOL, INTERVAL, price.lastTradePrice(), price.observedAt());
                log.info("[策略K线] id={} symbol={} interval={} openTime={} close={} closed={}",
                    id, SYMBOL, INTERVAL, candle.openTime(), candle.close(), candle.closed());
            } catch (RuntimeException ex) {
                healthySubscriptions.put(id, false);
                log.warn("[策略行情查询失败] id={} symbol={} interval={} reason={}", id, SYMBOL, INTERVAL, ex.toString());
            }
        }, 1, 5, TimeUnit.SECONDS);
        subscriptions.put(id, task);
        log.info("[策略启动] id={} symbol={} interval={} mode=REST-POLL-5S，不执行交易", id, SYMBOL, INTERVAL);
        return true;
    }

    @Override
    public synchronized boolean stop(Long id) {
        if (mapper.selectById(id) == null) throw new ServiceException("策略不存在");
        ScheduledFuture<?> handle = subscriptions.get(id);
        if (handle != null) {
            handle.cancel(false);
            subscriptions.remove(id);
            healthySubscriptions.remove(id);
        }
        log.info("[策略停止] id={}", id);
        return true;
    }

    @PreDestroy
    public synchronized void shutdown() {
        subscriptions.values().forEach(handle -> handle.cancel(false));
        subscriptions.clear();
        healthySubscriptions.clear();
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
