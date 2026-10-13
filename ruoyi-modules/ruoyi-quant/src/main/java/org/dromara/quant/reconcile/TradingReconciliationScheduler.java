package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.config.QuantProperties;
import org.dromara.config.domain.QuantCredential;
import org.dromara.config.mapper.QuantCredentialMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期性交易所对账。
 */
@Slf4j
@Component
public class TradingReconciliationScheduler {
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;
    /**
     * 交易事实对账服务。
     */
    private final TradingReconciliationService reconciliationService;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;

    /**
     * 创建 TradingReconciliationScheduler 实例并初始化其依赖。
     */
    public TradingReconciliationScheduler(QuantCredentialMapper credentialMapper, TradingReconciliationService reconciliationService, QuantProperties properties) {
        this.credentialMapper = credentialMapper;
        this.reconciliationService = reconciliationService;
        this.properties = properties;
    }

    /**
     * 执行一轮交易事实对账。
     */
    @Scheduled(fixedDelayString = "#{@quantProperties.reconcile.intervalSeconds * 1000}", initialDelay = 15000)
    public void reconcile() {
        if (!properties.getReconcile().isEnabled()) {
            return;
        }
        for (QuantCredential credential : credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"))) {
            if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                reconciliationService.reconcileCredential(credential, properties.getReconcile().getHistoryHours());
            } catch (RuntimeException ex) {
                log.error("量化账户事实对账失败 credentialId={}", credential.getId(), ex);
            }
        }
    }
}
