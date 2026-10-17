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

    /**
     * 应用启动后刷新 LIVE 账户持仓，避免沿用进程外的旧快照。
     */
    @Override
    public void run(ApplicationArguments args) {
        var enabled = credentials.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"));
        for (QuantCredential credential : enabled) {
            if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                // A concurrent private account event may supersede an in-flight REST snapshot
                // during startup. Re-fetch a NEW authenticated snapshot; never reuse the
                // rejected facts or bypass the generation/DB fence.
                for (int attempt = 1; ; attempt++) {
                    try {
                        reconciliation.refreshPositionsOnly(credential);
                        log.info("LIVE startup position snapshot verified credentialId={} attempt={}", credential.getId(), attempt);
                        break;
                    } catch (TradingReconciliationService.SnapshotSupersededException ex) {
                        if (attempt >= 5) {
                            throw ex;
                        }
                        log.warn("LIVE startup snapshot invalidated by newer account event, retrying credentialId={} attempt={}", credential.getId(), attempt);
                        try {
                            Thread.sleep(200L * attempt);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("LIVE startup position refresh interrupted", interrupted);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                log.error("LIVE startup refused: account position snapshot unverified credentialId={}", credential.getId(), ex);
                throw new IllegalStateException("LIVE startup requires authenticated Binance position refresh for credentialId=" + credential.getId(), ex);
            }
        }
    }
}
