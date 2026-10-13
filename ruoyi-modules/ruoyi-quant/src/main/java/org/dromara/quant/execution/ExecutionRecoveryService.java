package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.config.QuantProperties;
import org.dromara.config.domain.QuantCredential;
import org.dromara.config.mapper.QuantCredentialMapper;
import org.dromara.quant.exchange.ExchangeGateway;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 恢复 PENDING/UNKNOWN/ACCEPTED 执行记录，避免“交易所已接受、应用未落库”后再次下单。
 */
@Component
public class ExecutionRecoveryService {
    /**
     * 记录 Mapper。
     */
    private final QuantExecutionRecordMapper recordMapper;
    /**
     * 交易凭证 Mapper。
     */
    private final QuantCredentialMapper credentialMapper;
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;
    /**
     * 记录服务。
     */
    private final ExecutionRecordService recordService;
    /**
     * 订单账本服务。
     */
    private final OrderLedgerService orderLedgerService;
    /**
     * 量化模块配置。
     */
    private final QuantProperties properties;

    /**
     * 创建 ExecutionRecoveryService 实例并初始化其依赖。
     */
    public ExecutionRecoveryService(QuantExecutionRecordMapper recordMapper, QuantCredentialMapper credentialMapper, ExchangeGateway gateway,
                                    ExecutionRecordService recordService, OrderLedgerService orderLedgerService, QuantProperties properties) {
        this.recordMapper = recordMapper;
        this.credentialMapper = credentialMapper;
        this.gateway = gateway;
        this.recordService = recordService;
        this.orderLedgerService = orderLedgerService;
        this.properties = properties;
    }

    /**
     * 扫描并恢复未确认的执行记录。
     */
    @Scheduled(fixedDelayString = "#{@quantProperties.runtime.recoverySeconds * 1000}", initialDelay = 20000)
    public void recover() {
        if (!properties.getReconcile().isEnabled()) {
            return;
        }
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<QuantExecutionRecord> pending = recordMapper.selectList(new LambdaQueryWrapper<QuantExecutionRecord>().in(QuantExecutionRecord::getExecutionStatus, "PENDING",
            "UNKNOWN", "CONFIRMED").ge(QuantExecutionRecord::getUpdatedAt, since).orderByAsc(QuantExecutionRecord::getCreatedAt).last("LIMIT 100"));
        for (QuantExecutionRecord record : pending) {
            if (record.getCredentialId() == null || record.getSymbol() == null) {
                continue;
            }
            QuantCredential credential = credentialMapper.selectById(record.getCredentialId());
            if (credential == null || "PAPER".equalsIgnoreCase(credential.getRunMode())) {
                continue;
            }
            try {
                recordService.markRecoveryAttempt(record);
                ExchangeGateway.Credential c = new ExchangeGateway.Credential(credential.getApiKey(), credential.getSecretKey(), credential.getSignatureType(),
                    credential.getRunMode());
                ExchangeGateway.OrderSnapshot exact = null;
                try {
                    exact = gateway.queryOrder(c, record.getSymbol(), record.getExchangeOrderId(), record.getClientOrderId());
                } catch (RuntimeException ignored) {
                    // 精确订单不存在或交易所暂时不可用时，继续使用当前挂单兜底查询。
                }
                if (exact != null) {
                    orderLedgerService.syncSnapshot(record.getCredentialId(), exact);
                    if ("CANCEL".equalsIgnoreCase(record.getOperationType())) {
                        String status = exact.status();
                        if ("CANCELED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status)) {
                            recordService.markRecovered(record, exact.orderId(), exact.status());
                        } else if ("FILLED".equalsIgnoreCase(status) || "PARTIALLY_FILLED".equalsIgnoreCase(status)) {
                            recordService.markRecovered(record, exact.orderId(), exact.status());
                            record.setErrorMessage("取消请求到达时订单已经成交: " + status);
                            record.setUpdatedAt(LocalDateTime.now());
                            recordMapper.updateById(record);
                        }
                    } else {
                        recordService.markRecovered(record, exact.orderId(), exact.status());
                    }
                    continue;
                }
                boolean matched =
                    record.getClientOrderId() != null && gateway.queryOrders(c, record.getSymbol()).stream().anyMatch(o -> record.getClientOrderId().equals(o.clientOrderId()));
                if (matched && !"CANCEL".equalsIgnoreCase(record.getOperationType())) {
                    recordService.markRecovered(record, record.getExchangeOrderId(), record.getExchangeOrderStatus());
                } else if (record.getCreatedAt() != null && Duration.between(record.getCreatedAt(), LocalDateTime.now()).getSeconds() >= 90) {
                    recordService.markUnknown(record, "超过 90 秒仍无法确认交易所订单状态，保持 UNKNOWN 并等待后续对账");
                }
            } catch (RuntimeException ignored) {
                // 网络/交易所异常不能改写交易事实；记录继续保留在当前状态，下一轮继续查询。
            }
        }
    }
}
