package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Before opening the LIVE runtime, refresh EVERY enabled exchange account's
 * complete Binance position snapshot. A failed refresh aborts startup, not
 * silently permits new entries from a stale quant_position row.
 * The independent position refresh works even when periodic historical
 * reconciliation has been intentionally disabled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@ConditionalOnProperty(prefix = "quant.live", name = "enabled", havingValue = "true")
public class LiveStartupPositionRefresh implements ApplicationRunner {
    private final QuantCredentialMapper credentials;
    private final TradingReconciliationService reconciliation;

    @Override
    public void run(ApplicationArguments args) {
        var enabled = credentials.selectList(new LambdaQueryWrapper<QuantCredential>()
            .eq(QuantCredential::getStatus, "0"));
        for (QuantCredential credential : enabled) {
            if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                reconciliation.refreshPositionsOnly(credential);
                log.info("LIVE startup position snapshot verified credentialId={}", credential.getId());
            } catch (RuntimeException ex) {
                log.error("LIVE startup refused: account position snapshot unverified credentialId={}",
                    credential.getId(), ex);
                throw new IllegalStateException(
                    "LIVE startup requires authenticated Binance position refresh for credentialId="
                        + credential.getId(), ex);
            }
        }
    }
}
