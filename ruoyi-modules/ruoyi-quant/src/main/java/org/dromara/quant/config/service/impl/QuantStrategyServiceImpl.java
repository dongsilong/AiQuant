package org.dromara.quant.config.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
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
import org.dromara.quant.market.MarketStreamHub;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单价格监听策略：固定 ETHUSDT / 15m，只读取市场事件，不产生交易信号。
 * 运行状态只代表当前进程中的订阅，不会在重启后自动开启。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuantStrategyServiceImpl implements IQuantStrategyService {
    private static final String PLUGIN = "price_logger";
    private static final String SYMBOL = "ETHUSDT";
    private static final String INTERVAL = "15m";

    private final QuantStrategyMapper mapper;
    private final MarketStreamHub streams;
    private final Map<Long, AutoCloseable> subscriptions = new ConcurrentHashMap<>();

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
        AutoCloseable handle = streams.subscribe(SYMBOL, INTERVAL, event -> {
            if (event.replay()) return;
            if ("TRADE".equals(event.type()) && event.price() != null) {
                log.info("[策略实时价格] id={} name={} symbol={} interval={} price={} time={}",
                    id, entity.getStrategyName(), SYMBOL, INTERVAL, event.price(), event.eventTime());
            } else if ("KLINE".equals(event.type()) && event.kline() != null) {
                log.info("[策略K线] id={} symbol={} interval={} close={} closed={} time={}",
                    id, SYMBOL, INTERVAL, event.kline().close(), event.kline().closed(), event.eventTime());
            }
        }, healthy -> log.info("[策略行情状态] id={} healthy={}", id, healthy));
        subscriptions.put(id, handle);
        log.info("[策略启动] id={} symbol={} interval={}，只读行情，不执行交易", id, SYMBOL, INTERVAL);
        return true;
    }

    @Override
    public synchronized boolean stop(Long id) {
        if (mapper.selectById(id) == null) throw new ServiceException("策略不存在");
        AutoCloseable handle = subscriptions.get(id);
        if (handle != null) {
            try {
                handle.close();
                subscriptions.remove(id);
            } catch (Exception ex) {
                throw new ServiceException("停止策略失败：" + ex.getMessage());
            }
        }
        log.info("[策略停止] id={}", id);
        return true;
    }

    @PreDestroy
    public synchronized void shutdown() {
        subscriptions.forEach((id, handle) -> {
            try {
                handle.close();
            } catch (Exception ex) {
                log.warn("关闭策略订阅失败 id={}", id, ex);
            }
        });
        subscriptions.clear();
    }

    private QuantStrategyVo applyStatus(QuantStrategyVo vo) {
        vo.setStatus(subscriptions.containsKey(vo.getId()) ? "RUNNING" : "STOPPED");
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
