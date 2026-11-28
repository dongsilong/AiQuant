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
     * 启动单个策略实例。策略整体启用状态由上层服务校验。
     */
    @Transactional(rollbackFor = Exception.class)
    public void startInstance(Long strategyId, Long instanceId) {
        QuantStrategyInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null || !strategyId.equals(instance.getStrategyId())) {
            throw new org.dromara.common.core.exception.ServiceException("策略实例不存在或不属于当前策略");
        }
        if (!"RUNNING".equals(instance.getStatus())) {
            instance.setStatus("RUNNING");
            if (instanceMapper.updateById(instance) != 1) {
                throw new org.dromara.common.core.exception.ServiceException("启动策略实例失败");
            }
        }
    }

    /**
     * 停止单个策略实例，不影响同一策略下的其他实例。
     */
    @Transactional(rollbackFor = Exception.class)
    public void stopInstance(Long strategyId, Long instanceId) {
        QuantStrategyInstance instance = instanceMapper.selectById(instanceId);
        if (instance == null || !strategyId.equals(instance.getStrategyId())) {
            throw new org.dromara.common.core.exception.ServiceException("策略实例不存在或不属于当前策略");
        }
        if (!"STOPPED".equals(instance.getStatus())) {
            instance.setStatus("STOPPED");
            if (instanceMapper.updateById(instance) != 1) {
                throw new org.dromara.common.core.exception.ServiceException("停止策略实例失败");
            }
        }
        afterCommit(() -> runtimeService.invalidateInstance(instanceId));
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
