//package org.dromara.quant.config.controller;
//
//import cn.dev33.satoken.annotation.SaCheckPermission;
//import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
//import lombok.RequiredArgsConstructor;
//import org.dromara.common.core.domain.R;
//import org.dromara.common.core.exception.ServiceException;
//import org.dromara.common.satoken.utils.LoginHelper;
//import org.dromara.quant.config.domain.QuantCredential;
//import org.dromara.quant.config.domain.QuantStrategy;
//import org.dromara.quant.config.domain.QuantSymbol;
//import org.dromara.quant.config.mapper.QuantCredentialMapper;
//import org.dromara.quant.config.mapper.QuantStrategyMapper;
//import org.dromara.quant.config.mapper.QuantSymbolMapper;
//import org.dromara.quant.config.service.impl.QuantStrategyServiceImpl;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
/// **
// * 策略与账户、交易对绑定管理。
// */
//@RestController
//@RequiredArgsConstructor
//@RequestMapping("/quant/strategy")
//public class QuantStrategyBindingController {
//    /**
//     * 账户相关 Mapper。
//     */
//    private final QuantStrategyAccountMapper accountMapper;
//    /**
//     * 交易对 Mapper。
//     */
//    private final QuantStrategySymbolMapper symbolMapper;
//    /**
//     * 交易凭证 Mapper。
//     */
//    private final QuantCredentialMapper credentialMapper;
//    private final QuantStrategyMapper strategyMapper;
//    private final QuantSymbolMapper quantSymbolMapper;
//    private final QuantStrategyUserMapper userMapper;
//    private final QuantStrategyServiceImpl strategyService;
//
//    private void checkStrategy(Long strategyId) {
//        strategyService.checkAccess(strategyMapper.selectById(strategyId));
//    }
//
//    /**
//     * 批量替换策略账户绑定，校验账户归属后一次提交。
//     */
//    @Transactional(rollbackFor = Exception.class)
//    @SaCheckPermission("quant:strategy:edit")
//    @PutMapping("/{strategyId}/accounts")
//    public R<Void> replaceAccounts(@PathVariable Long strategyId, @RequestBody StrategyAccountBatchRequest request) {
//        checkStrategy(strategyId);
//        List<Long> ids = request == null || request.credentialIds() == null ? List.of() : request.credentialIds().stream().distinct().toList();
//        Long userId = LoginHelper.getUserId();
//        for (Long id : ids) {
//            QuantCredential credential = credentialMapper.selectById(id);
//            if (credential == null || (!LoginHelper.isSuperAdmin(userId) && !userId.equals(credential.getUserId()))) {
//                throw new IllegalArgumentException("无权绑定该交易账户");
//            }
//        }
//        accountMapper.delete(new LambdaQueryWrapper<QuantStrategyAccount>().eq(QuantStrategyAccount::getStrategyId, strategyId));
//        for (Long id : ids) {
//            QuantStrategyAccount item = new QuantStrategyAccount();
//            item.setStrategyId(strategyId);
//            item.setCredentialId(id);
//            item.setStatus("0");
//            accountMapper.insert(item);
//        }
//        return R.ok();
//    }
//
//    @SaCheckPermission("quant:strategy:edit")
//    @GetMapping("/{strategyId}/users")
//    public R<List<QuantStrategyUser>> users(@PathVariable Long strategyId) {
//        checkStrategy(strategyId);
//        return R.ok(userMapper.selectList(new LambdaQueryWrapper<QuantStrategyUser>().eq(QuantStrategyUser::getStrategyId, strategyId)));
//    }
//
//    @Transactional(rollbackFor = Exception.class)
//    @SaCheckPermission("quant:strategy:edit")
//    @PutMapping("/{strategyId}/users")
//    public R<Void> replaceUsers(@PathVariable Long strategyId, @RequestBody StrategyUserBatchRequest request) {
//        checkStrategy(strategyId);
//        List<Long> ids = request == null || request.userIds() == null ? List.of() : request.userIds().stream().distinct().toList();
//        userMapper.delete(new LambdaQueryWrapper<QuantStrategyUser>().eq(QuantStrategyUser::getStrategyId, strategyId));
//        for (Long userId : ids) {
//            QuantStrategyUser item = new QuantStrategyUser();
//            item.setStrategyId(strategyId);
//            item.setUserId(userId);
//            item.setStatus("0");
//            userMapper.insert(item);
//        }
//        return R.ok();
//    }
//
//    /**
//     * 批量保存交易对覆盖配置，空字段继承策略公共默认值。
//     */
//    @Transactional(rollbackFor = Exception.class)
//    @SaCheckPermission("quant:strategy:edit")
//    @PutMapping("/{strategyId}/symbols")
//    public R<Void> replaceSymbols(@PathVariable Long strategyId, @RequestBody StrategySymbolBatchRequest request) {
//        checkStrategy(strategyId);
//        QuantStrategy strategy = strategyMapper.selectById(strategyId);
//        if (strategy == null) {
//            throw new IllegalArgumentException("策略不存在");
//        }
//        if ("1".equals(strategy.getStatus())) {
//            throw new ServiceException("运行中的策略必须先停止，才能修改交易对配置");
//        }
//        List<QuantStrategySymbol> items = request == null || request.items() == null ? List.of() : request.items();
//        var existing = symbolMapper.selectList(new LambdaQueryWrapper<QuantStrategySymbol>().eq(QuantStrategySymbol::getStrategyId, strategyId));
//        var incomingIds = items.stream().map(QuantStrategySymbol::getSymbolId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
//        for (QuantStrategySymbol old : existing) {
//            if (!incomingIds.contains(old.getSymbolId())) {
//                symbolMapper.deleteById(old.getId());
//            }
//        }
//        for (QuantStrategySymbol item : items) {
//            QuantSymbol symbol = quantSymbolMapper.selectById(item.getSymbolId());
//            if (symbol == null || !"0".equals(symbol.getStatus())) {
//                throw new IllegalArgumentException("交易对不存在或不可交易: " + item.getSymbolId());
//            }
//            item.setStrategyId(strategyId);
//            if (item.getIntervalCode() == null || item.getIntervalCode().isBlank()) {
//                item.setIntervalCode(strategy.getDefaultInterval());
//            }
//            if (item.getOpenRatio() == null) {
//                item.setOpenRatio(strategy.getDefaultOpenRatio());
//            }
//            if (item.getStopLossRatio() == null) {
//                item.setStopLossRatio(strategy.getDefaultStopLossRatio());
//            }
//            if (item.getHardStopMarginLossRatio() == null) {
//                item.setHardStopMarginLossRatio(strategy.getHardStopMarginLossRatio());
//            }
//            if (item.getLeverage() == null) {
//                item.setLeverage(strategy.getDefaultLeverage());
//            }
//            if (item.getMarginMode() == null || item.getMarginMode().isBlank()) {
//                item.setMarginMode(strategy.getDefaultMarginMode());
//            }
//            if (item.getParamsJson() == null || item.getParamsJson().isBlank()) {
//                item.setParamsJson(strategy.getParamsJson());
//            }
//            if (item.getStatus() == null || item.getStatus().isBlank()) {
//                item.setStatus("0");
//            }
//            QuantStrategySymbol old = existing.stream().filter(e -> java.util.Objects.equals(e.getSymbolId(), item.getSymbolId())).findFirst().orElse(null);
//            if (old == null) {
//                item.setId(null);
//                symbolMapper.insert(item);
//            } else {
//                item.setId(old.getId());
//                symbolMapper.updateById(item);
//            }
//        }
//        return R.ok();
//    }
//
//    /**
//     * 查询策略账户绑定。
//     */
//    @SaCheckPermission("quant:strategy:edit")
//    @GetMapping("/{strategyId}/accounts")
//    public R<List<QuantStrategyAccount>> accounts(@PathVariable Long strategyId) {
//        checkStrategy(strategyId);
//        return R.ok(accountMapper.selectList(new LambdaQueryWrapper<QuantStrategyAccount>().eq(QuantStrategyAccount::getStrategyId, strategyId)));
//    }
//
//    /**
//     * 绑定策略交易凭证。
//     */
//    @SaCheckPermission("quant:strategy:edit")
//    @PostMapping("/{strategyId}/accounts")
//    public R<Void> bindAccount(@PathVariable Long strategyId, @RequestBody QuantStrategyAccount entity) {
//        checkStrategy(strategyId);
//        QuantCredential credential = credentialMapper.selectById(entity.getCredentialId());
//        Long userId = LoginHelper.getUserId();
//        if (credential == null || (!LoginHelper.isSuperAdmin(userId) && !userId.equals(credential.getUserId()))) {
//            throw new IllegalArgumentException("无权绑定该交易账户");
//        }
//        entity.setId(null);
//        entity.setStrategyId(strategyId);
//        if (entity.getStatus() == null) {
//            entity.setStatus("0");
//        }
//        accountMapper.insert(entity);
//        return R.ok();
//    }
//
//    /**
//     * 解除策略交易凭证绑定。
//     */
//    @SaCheckPermission("quant:strategy:edit")
//    @DeleteMapping("/{strategyId}/accounts/{credentialId}")
//    public R<Void> unbindAccount(@PathVariable Long strategyId, @PathVariable Long credentialId) {
//        checkStrategy(strategyId);
//        accountMapper.delete(new LambdaQueryWrapper<QuantStrategyAccount>().eq(QuantStrategyAccount::getStrategyId, strategyId).eq(QuantStrategyAccount::getCredentialId,
//            credentialId));
//        return R.ok();
//    }
//
//    /**
//     * 查询策略交易对绑定。
//     */
//    @SaCheckPermission("quant:strategy:edit")
//    @GetMapping("/{strategyId}/symbols")
//    public R<List<QuantStrategySymbol>> symbols(@PathVariable Long strategyId) {
//        checkStrategy(strategyId);
//        return R.ok(symbolMapper.selectList(new LambdaQueryWrapper<QuantStrategySymbol>().eq(QuantStrategySymbol::getStrategyId, strategyId)));
//    }
//
//    /**
//     * 绑定策略交易对。
//     */
//    @SaCheckPermission("quant:strategy:edit")
//    @PostMapping("/{strategyId}/symbols")
//    public R<Void> bindSymbol(@PathVariable Long strategyId, @RequestBody QuantStrategySymbol entity) {
//        checkStrategy(strategyId);
//        entity.setId(null);
//        entity.setStrategyId(strategyId);
//        if (entity.getStatus() == null) {
//            entity.setStatus("0");
//        }
//        symbolMapper.insert(entity);
//        return R.ok();
//    }
//
//    /**
//     * 解除策略交易对绑定。
//     */
//    @SaCheckPermission("quant:strategy:edit")
//    @DeleteMapping("/{strategyId}/symbols/{symbolId}")
//    public R<Void> unbindSymbol(@PathVariable Long strategyId, @PathVariable Long symbolId) {
//        checkStrategy(strategyId);
//        symbolMapper.delete(new LambdaQueryWrapper<QuantStrategySymbol>().eq(QuantStrategySymbol::getStrategyId, strategyId).eq(QuantStrategySymbol::getSymbolId, symbolId));
//        return R.ok();
//    }
//}
