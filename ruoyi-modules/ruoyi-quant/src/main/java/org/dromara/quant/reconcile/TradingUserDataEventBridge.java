package org.dromara.quant.reconcile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.config.QuantProperties;
import org.dromara.quant.credential.domain.QuantCredential;
import org.dromara.quant.credential.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.market.UserDataStreamHub;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户数据事件桥。订单/账户事件作为实时事实来源转发给订阅者；周期性 REST 对账仅作为最终兜底。
 */
@Component
public class TradingUserDataEventBridge {
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;
    /**
     * 账户用户数据流中心。
     */
    private final UserDataStreamHub userDataStreamHub;
    /**
     * 交易事实对账服务。
     */
    private final TradingReconciliationService reconciliationService;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;
    /**
     * 当前已建立的订阅集合。
     */
    private final Map<Long, AutoCloseable> subscriptions = new ConcurrentHashMap<>();
    /**
     * 每个凭证最近一次快速对账时间。
     */
    private final Map<Long, Instant> lastReconcileAt = new ConcurrentHashMap<>();

    /**
     * 创建 TradingUserDataEventBridge 实例并初始化其依赖。
     */
    public TradingUserDataEventBridge(QuantCredentialMapper credentialMapper, UserDataStreamHub userDataStreamHub, TradingReconciliationService reconciliationService,
                                      QuantProperties properties) {
        this.credentialMapper = credentialMapper;
        this.userDataStreamHub = userDataStreamHub;
        this.reconciliationService = reconciliationService;
        this.properties = properties;
    }

    /**
     * 发现 RUNNING 实例、维护本地 Runtime，并停止失效实例。
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 10000)
    public void supervise() {
        if (!properties.getReconcile().isEnabled()) {
            closeAll();
            return;
        }
        java.util.List<QuantCredential> enabledCredentials = credentialMapper.selectList(new LambdaQueryWrapper<QuantCredential>().eq(QuantCredential::getStatus, "0"));
        java.util.Set<Long> activeIds =
            enabledCredentials.stream().filter(credential -> !"PAPER".equalsIgnoreCase(credential.getRunMode())).map(QuantCredential::getId).collect(java.util.stream.Collectors.toSet());
        for (QuantCredential credential : enabledCredentials) {
            if ("PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            subscriptions.computeIfAbsent(credential.getId(), ignored -> userDataStreamHub.subscribe(credential, event -> onEvent(credential.getId(), event)));
        }
        subscriptions.keySet().removeIf(id -> {
            if (activeIds.contains(id)) {
                return false;
            }
            AutoCloseable subscription = subscriptions.remove(id);
            if (subscription != null) {
                try {
                    subscription.close();
                } catch (Exception ignored) {
                }
            }
            return true;
        });
    }

    /**
     * 接收交易所账户私有事件，并触发对应凭证的快速事实同步。
     */
    private void onEvent(Long credentialId, ExchangeGateway.UserDataEvent event) {
        if (!properties.getReconcile().isEnabled() || event == null) {
            return;
        }
        if (!"ACCOUNT_UPDATE".equals(event.eventType()) && !"ORDER_TRADE_UPDATE".equals(event.eventType())) {
            return;
        }
        QuantCredential credential = credentialMapper.selectById(credentialId);
        if (credential == null) {
            return;
        }
        Instant now = Instant.now();
        Instant previous = lastReconcileAt.putIfAbsent(credentialId, now);
        if (previous != null && now.isBefore(previous.plusSeconds(2))) {
            return;
        }
        lastReconcileAt.put(credentialId, now);
        // 用户数据事件已经是实时事实来源，不再为每条事件触发完整 REST 对账。
        // 周期性对账任务仅负责低频校准和断线恢复，避免事件风暴触发 Binance 429。
        lastReconcileAt.put(credentialId, now);
    }

    /**
     * 关闭全部用户数据流订阅并释放资源。
     */
    private void closeAll() {
        subscriptions.keySet().forEach(id -> {
            AutoCloseable subscription = subscriptions.remove(id);
            if (subscription != null) {
                try {
                    subscription.close();
                } catch (Exception ignored) {
                }
            }
        });
    }

}

