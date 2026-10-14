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

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refresh() {
        for (QuantCredential credential : credentials.selectList(
            new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"))) {
            if (!"LIVE".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                reconciliation.refreshPositionsOnly(credential);
            } catch (RuntimeException ex) {
                log.warn("LIVE position heartbeat failed credentialId={}, new entries remain subject to snapshot TTL",
                    credential.getId(), ex);
            }
        }
    }
}
