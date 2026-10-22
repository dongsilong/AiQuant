package org.dromara.quant.strategy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.market.domain.QuantSymbol;
import org.dromara.quant.market.mapper.QuantSymbolMapper;
import org.dromara.quant.strategy.StrategyPluginRegistry;
import org.dromara.quant.strategy.domain.QuantStrategy;
import org.dromara.quant.strategy.domain.QuantStrategyUser;
import org.dromara.quant.strategy.domain.StrategyPreflightResult;
import org.dromara.quant.strategy.mapper.QuantStrategyAccountMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyMapper;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyUserMapper;
import org.dromara.quant.util.QuantJsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

/**
 * 策略定义服务。
 */
@RequiredArgsConstructor
@Service
public class QuantStrategyService {
    /**
     * 当前领域 Mapper。
     */
    private final QuantStrategyMapper mapper;
    /**
     * 策略插件注册表。
     */
    private final StrategyPluginRegistry pluginRegistry;
    /**
     * 策略实例服务。
     */
    private final StrategyInstanceService instanceService;
    private final QuantStrategyAccountMapper accountMapper;
    private final QuantStrategySymbolMapper symbolMapper;
    private final QuantCredentialMapper credentialMapper;
    private final QuantSymbolMapper quantSymbolMapper;
    private final QuantStrategyUserMapper userMapper;
    private final JdbcTemplate jdbcTemplate;

    private static boolean validRate(java.math.BigDecimal value, boolean strictLessThanOne) {
        return value != null && value.signum() > 0 && (!strictLessThanOne ? value.compareTo(java.math.BigDecimal.ONE) <= 0 : value.compareTo(java.math.BigDecimal.ONE) < 0);
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String normalized = mode.toUpperCase(java.util.Locale.ROOT);
        return "ISOLATED".equals(normalized) || "CROSSED".equals(normalized) ? normalized : null;
    }

    private static boolean changed(Object oldValue, Object newValue) {
        return newValue != null && !java.util.Objects.equals(oldValue, newValue);
    }

    private boolean validInterval(String interval) {
        if (interval == null || interval.isBlank()) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM sys_dict_data WHERE dict_type=? AND dict_value=?", Integer.class, "quant_kline_period", interval);
        return count != null && count > 0;
    }

    /**
     * 分页查询业务数据。
     */
    public PageResult<QuantStrategy> queryPage(PageQuery query) {
        LambdaQueryWrapper<QuantStrategy> wrapper = new LambdaQueryWrapper<QuantStrategy>().orderByDesc(QuantStrategy::getCreateTime);
        if (!LoginHelper.isSuperAdmin(LoginHelper.getUserId())) {
            LambdaQueryWrapper<QuantStrategyUser> lqw = new LambdaQueryWrapper<>();
            lqw.eq(QuantStrategyUser::getUserId, LoginHelper.getUserId());
            lqw.eq(QuantStrategyUser::getStatus, "0");
            List<Long> ids = userMapper.selectList(lqw).stream().map(QuantStrategyUser::getStrategyId).toList();
            if (ids.isEmpty()) {
                return PageResult.build(List.of(), 0L);
            }
            wrapper.in(QuantStrategy::getId, ids);
        }
        Page<QuantStrategy> page = mapper.selectPage(query.build(), wrapper);
        return PageResult.build(page.getRecords(), page.getTotal());
    }

    /**
     * 获取对应业务数据。
     */
    public QuantStrategy get(Long id) {
        QuantStrategy strategy = mapper.selectById(id);
        checkAccess(strategy);
        return strategy;
    }

    /**
     * 新增业务数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuantStrategy insert(QuantStrategy entity) {
        validate(entity);
        mapper.insert(entity);
        if (!LoginHelper.isSuperAdmin(LoginHelper.getUserId())) {
            QuantStrategyUser authorization = new QuantStrategyUser();
            authorization.setStrategyId(entity.getId());
            authorization.setUserId(LoginHelper.getUserId());
            authorization.setStatus("0");
            userMapper.insert(authorization);
        }
        return entity;
    }

    /**
     * 更新业务数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuantStrategy update(QuantStrategy entity) {
        QuantStrategy old = entity == null || entity.getId() == null ? null : mapper.selectById(entity.getId());
        if (old == null) {
            throw new ServiceException("策略不存在");
        }
        checkAccess(old);
        if ("1".equals(old.getStatus()) && (changed(old.getDefaultInterval(), entity.getDefaultInterval()) || changed(old.getDefaultOpenRatio(), entity.getDefaultOpenRatio()) || changed(old.getDefaultStopLossRatio(), entity.getDefaultStopLossRatio()) || changed(old.getDefaultLeverage(), entity.getDefaultLeverage()) || changed(old.getDefaultMarginMode(), entity.getDefaultMarginMode()) || changed(old.getParamsJson(), entity.getParamsJson()))) {
            throw new ServiceException("运行中的策略必须先停止，才能修改运行参数");
        }
        validate(entity);
        mapper.updateById(entity);
        return entity;
    }

    /**
     * 删除业务数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Collection<Long> ids) {
        for (Long id : ids) {
            checkAccess(require(id));
            instanceService.stop(id);
        }
        return mapper.deleteByIds(ids) > 0;
    }

    /**
     * 启动组件或底层订阅。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean start(Long id) {
        QuantStrategy strategy = require(id);
        checkAccess(strategy);
        StrategyPreflightResult result = preflight(id);
        if (!result.valid()) {
            throw new ServiceException("策略配置预检失败: " + String.join("；", result.errors()));
        }
        strategy.setStatus("1");
        mapper.updateById(strategy);
        instanceService.provision(id);
        return true;
    }

    /**
     * 执行与启动接口相同的服务端配置预检。
     */
    public StrategyPreflightResult preflight(Long id) {
        QuantStrategy strategy = require(id);
        checkAccess(strategy);
        java.util.List<String> errors = new java.util.ArrayList<>();
        java.util.List<String> warnings = new java.util.ArrayList<>();
        try {
            pluginRegistry.require(strategy.getPluginCode()).validateConfig(parseParams(strategy.getParamsJson()));
        } catch (RuntimeException ex) {
            errors.add(ex.getMessage() == null ? "插件参数无效" : ex.getMessage());
        }
        if (strategy.getMaxOpenCount() == null || strategy.getMaxOpenCount() < 1) {
            errors.add("最大开仓数量必须大于 0");
        }
        if (!validRate(strategy.getDefaultOpenRatio(), false)) {
            errors.add("默认开仓比例必须大于 0 且不超过 1");
        }
        if (!validRate(strategy.getDefaultStopLossRatio(), true)) {
            errors.add("默认止损比例必须大于 0 且小于 1");
        }
        if (strategy.getDefaultLeverage() == null || strategy.getDefaultLeverage() < 1) {
            errors.add("默认杠杆必须大于 0");
        }
        if (!validInterval(strategy.getDefaultInterval())) {
            errors.add("默认周期不在 quant_kline_period 字典中");
        }
        String defaultMode = normalizeMode(strategy.getDefaultMarginMode());
        if (defaultMode == null) {
            errors.add("默认保证金模式只能为 ISOLATED 或 CROSSED");
        }
        Long userId = LoginHelper.getUserId();
        var accountBindings =
            accountMapper.selectList(new LambdaQueryWrapper<org.dromara.quant.strategy.domain.QuantStrategyAccount>().eq(org.dromara.quant.strategy.domain.QuantStrategyAccount::getStrategyId, id).eq(org.dromara.quant.strategy.domain.QuantStrategyAccount::getStatus, "0"));
        var symbolBindings =
            symbolMapper.selectList(new LambdaQueryWrapper<org.dromara.quant.strategy.domain.QuantStrategySymbol>().eq(org.dromara.quant.strategy.domain.QuantStrategySymbol::getStrategyId, id).eq(org.dromara.quant.strategy.domain.QuantStrategySymbol::getStatus, "0"));
        var accounts = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var binding : accountBindings) {
            QuantCredential credential = credentialMapper.selectById(binding.getCredentialId());
            if (credential == null || (!LoginHelper.isSuperAdmin(userId) && !userId.equals(credential.getUserId()))) {
                errors.add("策略账户不存在或无权访问: " + binding.getCredentialId());
                continue;
            }
            accounts.add(java.util.Map.of("credentialId", credential.getId(), "accountName", credential.getAccountName()));
        }
        if (accounts.isEmpty()) {
            errors.add("至少配置一个策略账户");
        }
        var symbols = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (var binding : symbolBindings) {
            QuantSymbol symbol = quantSymbolMapper.selectById(binding.getSymbolId());
            if (symbol == null || !"0".equals(symbol.getStatus())) {
                errors.add("交易对不存在或不可交易: " + binding.getSymbolId());
                continue;
            }
            BigDecimal openRatio = binding.getOpenRatio() == null ? strategy.getDefaultOpenRatio() : binding.getOpenRatio();
            BigDecimal stopLoss = binding.getStopLossRatio() == null ? strategy.getDefaultStopLossRatio() : binding.getStopLossRatio();
            Integer leverage = binding.getLeverage() == null ? strategy.getDefaultLeverage() : binding.getLeverage();
            String mode = normalizeMode(binding.getMarginMode() == null ? defaultMode : binding.getMarginMode());
            try {
                pluginRegistry.require(strategy.getPluginCode()).validateConfig(parseParams(binding.getParamsJson() == null ? strategy.getParamsJson() : binding.getParamsJson()));
            } catch (RuntimeException ex) {
                errors.add(symbol.getSymbol() + " 插件参数无效: " + (ex.getMessage() == null ? "请检查 params" : ex.getMessage()));
            }
            if (!validInterval(binding.getIntervalCode() == null ? strategy.getDefaultInterval() : binding.getIntervalCode())) {
                errors.add(symbol.getSymbol() + " 周期无效");
            }
            if (!validRate(openRatio, false)) {
                errors.add(symbol.getSymbol() + " 开仓比例无效");
            }
            if (!validRate(stopLoss, true)) {
                errors.add(symbol.getSymbol() + " 止损比例无效");
            }
            if (leverage == null || leverage < 1) {
                errors.add(symbol.getSymbol() + " 杠杆必须大于 0");
            }
            if (mode == null) {
                errors.add(symbol.getSymbol() + " 保证金模式无效");
            }
            var symbolResult = new java.util.LinkedHashMap<String, Object>();
            symbolResult.put("symbolId", symbol.getId());
            symbolResult.put("symbol", symbol.getSymbol());
            symbolResult.put("intervalCode", binding.getIntervalCode() == null ? strategy.getDefaultInterval() : binding.getIntervalCode());
            symbolResult.put("openRatio", openRatio);
            symbolResult.put("stopLossRatio", stopLoss);
            symbolResult.put("leverage", leverage);
            symbolResult.put("marginMode", mode == null ? "ISOLATED" : mode);
            symbols.add(symbolResult);
        }
        if (symbols.isEmpty()) {
            errors.add("至少配置一个启用交易对");
        }
        return new StrategyPreflightResult(errors.isEmpty(), accounts.size() * symbols.size(), accounts, symbols, errors, warnings);
    }

    /**
     * 停止组件或订阅并释放资源。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean stop(Long id) {
        QuantStrategy strategy = require(id);
        instanceService.stop(id);
        strategy.setStatus("0");
        mapper.updateById(strategy);
        return true;
    }

    /**
     * 读取必需数据，不满足条件时抛出异常。
     */
    private QuantStrategy require(Long id) {
        QuantStrategy e = mapper.selectById(id);
        if (e == null) {
            throw new ServiceException("策略不存在");
        }
        return e;
    }

    /**
     * 校验当前用户是否获得该策略授权。
     */
    public void checkAccess(QuantStrategy strategy) {
        if (strategy == null) {
            throw new ServiceException("策略不存在");
        }
        if (LoginHelper.isSuperAdmin(LoginHelper.getUserId())) {
            return;
        }
        Long count = userMapper.selectCount(new LambdaQueryWrapper<QuantStrategyUser>().eq(QuantStrategyUser::getStrategyId, strategy.getId()).eq(QuantStrategyUser::getUserId,
            LoginHelper.getUserId()).eq(QuantStrategyUser::getStatus, "0"));
        if (count == null || count == 0) {
            throw new ServiceException("当前用户未获得该策略授权");
        }
    }

    /**
     * 返回当前用户可访问的策略 ID；超级管理员返回 null 表示不限制。
     */
    public List<Long> accessibleStrategyIds() {
        if (LoginHelper.isSuperAdmin(LoginHelper.getUserId())) {
            return null;
        }
        return userMapper.selectList(new LambdaQueryWrapper<QuantStrategyUser>().eq(QuantStrategyUser::getUserId, LoginHelper.getUserId()).eq(QuantStrategyUser::getStatus, "0")).stream().map(QuantStrategyUser::getStrategyId).toList();
    }

    /**
     * 校验策略及参数配置。
     */
    private void validate(QuantStrategy entity) {
        if (entity == null) {
            throw new ServiceException("策略不能为空");
        }
        if (entity.getPluginCode() == null || entity.getPluginCode().isBlank()) {
            throw new ServiceException("策略插件不能为空");
        }
        pluginRegistry.require(entity.getPluginCode());
        // 当前管理端以插件作为策略的唯一类型来源；兼容旧表中 strategy_type 的非空约束。
        if (entity.getStrategyType() == null || entity.getStrategyType().isBlank()) {
            entity.setStrategyType(entity.getPluginCode());
        }
        if (entity.getMaxOpenCount() == null || entity.getMaxOpenCount() < 1) {
            entity.setMaxOpenCount(1);
        }
        if (entity.getDefaultInterval() == null || entity.getDefaultInterval().isBlank()) {
            entity.setDefaultInterval("15m");
        }
        if (entity.getDefaultOpenRatio() == null) {
            entity.setDefaultOpenRatio(new java.math.BigDecimal("0.10"));
        }
        if (entity.getDefaultStopLossRatio() == null) {
            entity.setDefaultStopLossRatio(new java.math.BigDecimal("0.05"));
        }
        if (entity.getStopLossRatio() == null) {
            entity.setStopLossRatio(new java.math.BigDecimal("0.10"));
        }
        if (entity.getStopLossRatio().signum() <= 0 || entity.getStopLossRatio().compareTo(java.math.BigDecimal.ONE) >= 0) {
            throw new ServiceException("stopLossRatio 必须 > 0 且 < 1");
        }
        if (entity.getHardStopMarginLossRatio() == null) {
            entity.setHardStopMarginLossRatio(new java.math.BigDecimal("0.20"));
        }
        if (entity.getDefaultLeverage() == null || entity.getDefaultLeverage() < 1) {
            entity.setDefaultLeverage(1);
        }
        if (entity.getDefaultMarginMode() == null || entity.getDefaultMarginMode().isBlank()) {
            entity.setDefaultMarginMode("ISOLATED");
        } else if (normalizeMode(entity.getDefaultMarginMode()) == null) {
            throw new ServiceException("defaultMarginMode 只能为 ISOLATED 或 CROSSED");
        }
        if (entity.getParamsJson() == null || entity.getParamsJson().isBlank()) {
            entity.setParamsJson("{}");
        }
        if (entity.getStatus() == null || entity.getStatus().isBlank()) {
            entity.setStatus("0");
        }
        if (entity.getPluginVersion() == null || entity.getPluginVersion().isBlank()) {
            entity.setPluginVersion(pluginRegistry.require(entity.getPluginCode()).descriptor().version());
        }
    }

    /**
     * 解析对应业务数据。
     */
    private java.util.Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return java.util.Collections.emptyMap();
        }
        try {
            return QuantJsonMapper.readMap(json);
        } catch (Exception ex) {
            throw new ServiceException("策略参数 JSON 无效");
        }
    }
}
