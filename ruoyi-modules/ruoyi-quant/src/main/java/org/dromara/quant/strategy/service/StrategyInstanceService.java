package org.dromara.quant.strategy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.strategy.domain.QuantStrategyAccount;
import org.dromara.quant.strategy.domain.QuantStrategyInstance;
import org.dromara.quant.strategy.domain.QuantStrategySymbol;
import org.dromara.quant.strategy.mapper.QuantStrategyAccountMapper;
import org.dromara.quant.strategy.mapper.QuantStrategyInstanceMapper;
import org.dromara.quant.strategy.mapper.QuantStrategySymbolMapper;
import org.dromara.quant.runtime.StrategyRuntimeService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 策略实例的生成与生命周期管理。
 */
@RequiredArgsConstructor
@Service
public class StrategyInstanceService {
    /**
     * 账户相关 Mapper。
     */
    private final QuantStrategyAccountMapper accountMapper;
    /**
     * 交易对 Mapper。
     */
    private final QuantStrategySymbolMapper symbolMapper;
    /**
     * 策略实例 Mapper。
     */
    private final QuantStrategyInstanceMapper instanceMapper;
    private final StrategyRuntimeService runtimeService;

    /**
     * 根据绑定关系生成或补齐运行实例。
     */
    @Transactional(rollbackFor = Exception.class)
    public void provision(Long strategyId) {
        List<QuantStrategyAccount> accounts =
            accountMapper.selectList(new LambdaQueryWrapper<QuantStrategyAccount>().eq(QuantStrategyAccount::getStrategyId, strategyId).eq(QuantStrategyAccount::getStatus, "0"));
        List<QuantStrategySymbol> symbols =
            symbolMapper.selectList(new LambdaQueryWrapper<QuantStrategySymbol>().eq(QuantStrategySymbol::getStrategyId, strategyId).eq(QuantStrategySymbol::getStatus, "0"));
        java.util.Set<String> activeBindings = new java.util.HashSet<>();
        for (QuantStrategyAccount account : accounts) {
            for (QuantStrategySymbol symbol : symbols) {
                activeBindings.add(bindingKey(symbol.getId(), account.getCredentialId()));
                QuantStrategyInstance instance = instanceMapper.selectOne(new LambdaQueryWrapper<QuantStrategyInstance>().eq(QuantStrategyInstance::getStrategySymbolId,
                    symbol.getId()).eq(QuantStrategyInstance::getCredentialId, account.getCredentialId()).eq(QuantStrategyInstance::getStrategyId, strategyId));
                if (instance == null) {
                    instance = new QuantStrategyInstance();
                    instance.setStrategyId(strategyId);
                    instance.setStrategySymbolId(symbol.getId());
                    instance.setCredentialId(account.getCredentialId());
                }
                instance.setStatus("RUNNING");
                instance.setLastError(null);
                if (instance.getId() == null) {
                    instanceMapper.insert(instance);
                } else {
                    instanceMapper.updateById(instance);
                }
            }
        }
        // 交易对或账户绑定被删除/停用后保留实例记录用于审计，但不得继续保持可运行状态。
        List<QuantStrategyInstance> instances = instanceMapper.selectList(new LambdaQueryWrapper<QuantStrategyInstance>().eq(QuantStrategyInstance::getStrategyId, strategyId));
        for (QuantStrategyInstance instance : instances) {
            if (!activeBindings.contains(bindingKey(instance.getStrategySymbolId(), instance.getCredentialId())) && !"STOPPED".equals(instance.getStatus())) {
                instance.setStatus("STOPPED");
                instance.setLastError("策略交易对或账户绑定已失效");
                instanceMapper.updateById(instance);
                afterCommit(() -> runtimeService.invalidateInstance(instance.getId()));
            }
        }
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String bindingKey(Long strategySymbolId, Long credentialId) {
        return String.valueOf(strategySymbolId) + ':' + credentialId;
    }

    /**
     * 停止组件或订阅并释放资源。
     */
    @Transactional(rollbackFor = Exception.class)
    public void stop(Long strategyId) {
        List<QuantStrategyInstance> instances = instanceMapper.selectList(new LambdaQueryWrapper<QuantStrategyInstance>().eq(QuantStrategyInstance::getStrategyId, strategyId));
        for (QuantStrategyInstance instance : instances) {
            instance.setStatus("STOPPED");
            instanceMapper.updateById(instance);
            afterCommit(() -> runtimeService.invalidateInstance(instance.getId()));
        }
    }
}
