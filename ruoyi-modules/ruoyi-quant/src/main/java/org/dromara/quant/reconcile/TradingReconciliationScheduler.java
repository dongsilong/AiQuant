package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 周期性交易所对账。
 */
@Slf4j
@RequiredArgsConstructor
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
     * 执行一轮账户级交易事实对账，补齐本地成交账本与执行记录。
     */
    @Scheduled(
        fixedDelayString = "#{@quantProperties.reconcile.intervalSeconds.toString()}",
        initialDelayString = "15",
        timeUnit = TimeUnit.SECONDS
    )
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
            } catch (TradingReconciliationService.SnapshotSupersededException ex) {
                // 私有流事件取代旧 REST 快照是正常并发冲突；服务层已安排重试。
                log.debug("账户持仓快照被较新的私有流事件取代，已安排后续核实 credentialId={}", credential.getId());
            } catch (RuntimeException ex) {
                log.error("量化账户事实对账失败 credentialId={}", credential.getId(), ex);
            }
        }
    }
}
