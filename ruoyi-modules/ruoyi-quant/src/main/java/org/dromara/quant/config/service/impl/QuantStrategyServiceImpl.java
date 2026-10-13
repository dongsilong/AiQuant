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
import org.dromara.quant.config.strategy.StrategyPluginRegistry;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.MarketStreamHub;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 最小策略管理：持久化定义并管理本进程的只读行情订阅。
 * 不调用下单接口，不在应用重启后自动恢复运行状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuantStrategyServiceImpl implements IQuantStrategyService {
    public static final String DEMO_PLUGIN = "price_logger";
    private static final String SYMBOL = "ETHUSDT";
    private static final String INTERVAL = "15m";

    private final QuantStrategyMapper mapper;
    private final StrategyPluginRegistry plugins;
    private final MarketStreamHub marketStreamHub;
    private final Map<Long, AutoCloseable> sessions = new ConcurrentHashMap<>();

    @Override
    public PageResult<QuantStrategyVo> queryPage(PageQuery query) {
        LambdaQueryWrapper<QuantStrategy> wrapper = new LambdaQueryWrapper<QuantStrategy>()
            .orderByDesc(QuantStrategy::getCreateTime);
        Page<QuantStrategyVo> page = mapper.selectVoPage(query.build(), wrapper);
        page.getRecords().forEach(this::runtimeStatus);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    @Override
    public QuantStrategyVo get(Long id) {
        QuantStrategyVo vo = mapper.selectVoById(id);
        if (vo == null) throw new ServiceException("策略不存在");
        return runtimeStatus(vo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuantStrategyVo insert(QuantStrategyBo bo) {
        validate(bo);
        QuantStrategy entity = MapstructUtils.convert(bo, QuantStrategy.class);
        entity.setId(null);
        entity.setPluginVersion(plugins.require(DEMO_PLUGIN).descriptor().version());
        entity.setDefaultInterval(INTERVAL);
        entity.setParamsJson("{}");
        entity.setStatus("0");
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        mapper.insert(entity);
        return get(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public synchronized QuantStrategyVo update(QuantStrategyBo bo) {
        if (bo.getId() == null || mapper.selectById(bo.getId()) == null) {
            throw new ServiceException("策略不存在");
        }
        if (sessions.containsKey(bo.getId())) throw new ServiceException("请先停止策略再编辑");
        validate(bo);
        QuantStrategy entity = MapstructUtils.convert(bo, QuantStrategy.class);
        entity.setPluginVersion(plugins.require(DEMO_PLUGIN).descriptor().version());
        entity.setDefaultInterval(INTERVAL);
        entity.setParamsJson("{}");
        entity.setStatus("0");
        entity.setUpdateTime(LocalDateTime.now());
        mapper.updateById(entity);
        return get(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public synchronized Boolean delete(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return false;
        for (Long id : ids) {
            if (sessions.containsKey(id)) throw new ServiceException("请先停止运行中的策略再删除");
        }
        return mapper.deleteByIds(ids) > 0;
    }

    @Override
    public synchronized boolean start(Long id) {
        QuantStrategy entity = mapper.selectById(id);
        if (entity == null) throw new ServiceException("策略不存在");
        if (!DEMO_PLUGIN.equals(entity.getPluginCode())) throw new ServiceException("目前只支持价格日志演示策略");
        if (sessions.containsKey(id)) return true;
        AutoCloseable subscription;
        try {
            subscription = marketStreamHub.subscribe(SYMBOL, INTERVAL, event -> {
                if ("TRADE".equals(event.type()) && event.price() != null && !event.replay()) {
                    log.info("[策略价格] id={} name={} symbol={} interval={} lastPrice={} time={}",
                        id, entity.getStrategyName(), SYMBOL, INTERVAL, event.price(), event.eventTime());
                } else if ("KLINE".equals(event.type()) && event.kline() != null && !event.replay()) {
                    log.info("[策略K线] id={} symbol={} interval={} close={} closed={} time={}",
                        id, SYMBOL, INTERVAL, event.kline().close(), event.kline().closed(), event.eventTime());
                }
            }, healthy -> log.info("[策略行情] id={} symbol={} healthy={}", id, SYMBOL, healthy));
        } catch (RuntimeException ex) {
            throw new ServiceException("订阅行情失败: " + ex.getMessage());
        }
        sessions.put(id, subscription);
        log.info("[策略启动] id={} name={} symbol={} interval={} (只读行情，不会下单)",
            id, entity.getStrategyName(), SYMBOL, INTERVAL);
        return true;
    }

    @Override
    public synchronized boolean stop(Long id) {
        if (mapper.selectById(id) == null) throw new ServiceException("策略不存在");
        AutoCloseable session = sessions.remove(id);
        if (session != null) {
            try {
                session.close();
            } catch (Exception ex) {
                log.warn("停止策略释放订阅失败 id={}", id, ex);
                throw new ServiceException("停止策略释放订阅失败");
            }
        }
        log.info("[策略停止] id={}", id);
        return true;
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (Map.Entry<Long, AutoCloseable> entry : sessions.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ex) {
                log.warn("关闭策略订阅失败 id={}", entry.getKey(), ex);
            }
        }
        sessions.clear();
    }

    private QuantStrategyVo runtimeStatus(QuantStrategyVo vo) {
        vo.setStatus(sessions.containsKey(vo.getId()) ? "RUNNING" : "STOPPED");
        return vo;
    }

    private void validate(QuantStrategyBo bo) {
        if (bo == null || !StringUtils.hasText(bo.getStrategyName())) throw new ServiceException("策略名称不能为空");
        if (!DEMO_PLUGIN.equals(bo.getPluginCode())) throw new ServiceException("请选择价格日志演示策略");
        plugins.require(DEMO_PLUGIN);
    }

    @Override
    public void checkAccess(QuantStrategyBo strategy) {
        // TODO: 接入用户策略授权前，不通过本方法授予额外权限；HTTP 接口仍受权限注解保护。
    }

    @Override
    public List<Long> accessibleStrategyIds() {
        return List.of();
    }
}
