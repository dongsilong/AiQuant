package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic FULL position truth heartbeat, independent of order/trade-history
 * reconciliation. Never changes persisted quantities on failed REST calls.
 * A node's LIVE risk gate expires after 120s without a successful snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "quant.live", name = "enabled", havingValue = "true")
public class LivePositionRefreshScheduler {
    private final QuantCredentialMapper credentials;
    private final TradingReconciliationService reconciliation;

    /**
     * 定期触发活动 LIVE 账户的持仓事实刷新。
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refresh() {
        for (QuantCredential credential : credentials.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"))) {
            if (!"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                reconciliation.refreshPositionsOnly(credential);
            } catch (TradingReconciliationService.SnapshotSupersededException ex) {
                // 新事件已使旧 REST 结果失效；事件刷新器和下一轮心跳会重新取证，不放宽新鲜度门禁。
                log.debug("LIVE 持仓心跳快照已被更新事实取代，等待重新核实 credentialId={}", credential.getId());
            } catch (RuntimeException ex) {
                log.warn("LIVE position heartbeat failed credentialId={}, new entries remain subject to snapshot TTL", credential.getId(), ex);
            }
        }
    }
}
