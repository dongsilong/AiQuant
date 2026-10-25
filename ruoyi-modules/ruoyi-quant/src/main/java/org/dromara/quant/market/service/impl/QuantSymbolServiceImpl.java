package org.dromara.quant.market.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.domain.QuantSymbolFavorite;
import org.dromara.quant.market.domain.bo.QuantSymbolBo;
import org.dromara.quant.market.domain.vo.QuantSymbolVo;
import org.dromara.quant.market.mapper.QuantSymbolFavoriteMapper;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.market.service.IQuantSymbolService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * QuantSymbolService 的默认服务实现。
 */
@Service
@RequiredArgsConstructor
public class QuantSymbolServiceImpl implements IQuantSymbolService {
    /**
     * 当前领域 Mapper。
     */
    private final QuantSymbolMapper mapper;
    /**
     * 交易对收藏 Mapper。
     */
    private final QuantSymbolFavoriteMapper favoriteMapper;
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /** 交易所元数据同步成功后，立即使进程内规则快照失效。 */
    private final org.dromara.quant.exchange.ExchangeTradingRuleCache tradingRuleCache;

    /**
     * 按条件分页查询。
     */
    @Override
    public PageResult<QuantSymbolVo> queryPageList(QuantSymbolBo bo, PageQuery query) {
        LambdaQueryWrapper<QuantSymbol> lqw = new LambdaQueryWrapper<QuantSymbol>();
        lqw.like(StringUtils.isNotBlank(bo.getSymbol()), QuantSymbol::getSymbol, bo.getSymbol());
        lqw.like(StringUtils.isNotBlank(bo.getQuoteAsset()), QuantSymbol::getQuoteAsset, bo.getQuoteAsset());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), QuantSymbol::getStatus, bo.getStatus());
        lqw.eq(StringUtils.isNotBlank(bo.getExchangeStatus()), QuantSymbol::getExchangeStatus, bo.getExchangeStatus());
        // 5. 收藏过滤：用 EXISTS + 参数化，避免 SQL 注入和 IN 子查询性能问题
        lqw.apply(Boolean.TRUE.equals(bo.getFavorite()), "EXISTS (SELECT 1 FROM quant_symbol_favorite f WHERE f.symbol_id = quant_symbol.id AND f.user_id = {0})",
            LoginHelper.getUserId());
        // 在分页前排序，确保当前用户收藏的交易对优先于未收藏项。
        lqw.last("ORDER BY EXISTS (SELECT 1 FROM quant_symbol_favorite f WHERE f.symbol_id = quant_symbol.id AND f.user_id = " + LoginHelper.getUserId() + ") DESC, quant_symbol" +
            ".symbol ASC");
        Page<QuantSymbolVo> page = mapper.selectVoPage(query.build(), lqw);

        LambdaQueryWrapper<QuantSymbolFavorite> lqwf = new LambdaQueryWrapper<>();
        lqwf.select(QuantSymbolFavorite::getSymbolId);
        lqwf.eq(QuantSymbolFavorite::getUserId, LoginHelper.getUserId());
        List<QuantSymbolFavorite> favorites = favoriteMapper.selectList(lqwf);

        // 提取当前用户收藏的 symbolId 集合，便于快速判断
        Set<Long> favoriteSymbolIds = favorites.stream().map(QuantSymbolFavorite::getSymbolId).collect(Collectors.toSet());

        // 遍历当前页记录，标记收藏状态
        for (QuantSymbolVo vo : page.getRecords()) {
            vo.setFavorite(favoriteSymbolIds.contains(vo.getId()));
        }

        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 按主键查询。
     */
    @Override
    public QuantSymbolVo queryById(Long id) {
        QuantSymbol e = mapper.selectById(id);
        return e == null ? null : toVo(e);
    }

    /**
     * 根据业务入参新增记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuantSymbolVo insertByBo(QuantSymbolBo bo) {
        QuantSymbol e = MapstructUtils.convert(bo, QuantSymbol.class);
        defaults(e);
        mapper.insert(e);
        return toVo(e);
    }

    /**
     * 根据业务入参更新记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public QuantSymbolVo updateByBo(QuantSymbolBo bo) {
        if (mapper.selectById(bo.getId()) == null) {
            throw new ServiceException("交易对不存在");
        }
        QuantSymbol e = MapstructUtils.convert(bo, QuantSymbol.class);
        defaults(e);
        mapper.updateById(e);
        return toVo(e);
    }

    /**
     * 业务校验后批量删除记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids) {
        return mapper.deleteByIds(ids) > 0;
    }

    /**
     * 从交易所同步基础元数据。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int syncFromExchange() {
        int count = 0;
        List<ExchangeGateway.SymbolRule> symbols = gateway.queryExchangeInfo().symbols();
        // 完整交易所元数据已成功返回；避免策略启动继续使用同步前的规则快照。
        tradingRuleCache.invalidateAll();

        // 先批量读取本地交易对，避免每个交易所交易对都执行一次 selectOne。
        // 交易所元数据通常只有数百条；一次读取并建立索引比 N 次往返更可控。
        Map<String, QuantSymbol> localBySymbol = new HashMap<>();
        for (QuantSymbol local : mapper.selectList(null)) {
            if (local.getSymbol() != null) {
                localBySymbol.putIfAbsent(local.getSymbol().toUpperCase(Locale.ROOT), local);
            }
        }

        LocalDateTime syncedAt = LocalDateTime.now();
        for (ExchangeGateway.SymbolRule rule : symbols) {
            if (rule == null || !"USDT".equalsIgnoreCase(rule.quoteAsset()) || rule.symbol() == null || rule.symbol().isBlank()) {
                continue;
            }
            String key = rule.symbol().toUpperCase(Locale.ROOT);
            QuantSymbol e = localBySymbol.get(key);
            if (e == null) {
                e = new QuantSymbol();
                e.setSymbol(rule.symbol());
            }
            e.setQuoteAsset(rule.quoteAsset());
            e.setPricePrecision(rule.pricePrecision());
            e.setQuantityPrecision(rule.quantityPrecision());
            e.setPriceTickSize(rule.priceTickSize());
            e.setQuantityStep(rule.stepSize());
            e.setMarketQuantityStep(rule.marketStepSize());
            e.setMinNotional(rule.minNotional());
            e.setExchangeStatus(rule.status());
            e.setSyncedAt(syncedAt);
            if (StringUtils.isBlank(e.getStatus())) {
                e.setStatus("0");
            }
            if (e.getId() == null) {
                mapper.insert(e);
                localBySymbol.put(key, e);
            } else {
                mapper.updateById(e);
            }
            count++;
        }
        return count;
    }

    private void defaults(QuantSymbol e) {
        if (StringUtils.isBlank(e.getStatus())) {
            e.setStatus("0");
        }
    }

    private QuantSymbolVo toVo(QuantSymbol e) {
        QuantSymbolVo vo = MapstructUtils.convert(e, QuantSymbolVo.class);
        LambdaQueryWrapper<QuantSymbolFavorite> lqw = new LambdaQueryWrapper<QuantSymbolFavorite>();
        lqw.eq(QuantSymbolFavorite::getSymbolId, e.getId());
        lqw.eq(QuantSymbolFavorite::getUserId, LoginHelper.getUserId());
        vo.setFavorite(favoriteMapper.selectCount(lqw) > 0);
        return vo;
    }
}
