package org.dromara.quant.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.dromara.quant.execution.domain.QuantExecutionRecord;
import org.dromara.quant.execution.mapper.QuantExecutionRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 数据库幂等执行记录服务。
 */
@Service
public class ExecutionRecordService {
    /**
     * 当前领域 Mapper。
     */
    private final QuantExecutionRecordMapper mapper;

    /**
     * 创建 ExecutionRecordService 实例并初始化其依赖。
     */
    public ExecutionRecordService(QuantExecutionRecordMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询符合条件的记录。
     */
    public QuantExecutionRecord find(Long credentialId, String idempotencyKey) {
        return mapper.selectOne(new LambdaQueryWrapper<QuantExecutionRecord>().eq(QuantExecutionRecord::getCredentialId, credentialId).eq(QuantExecutionRecord::getIdempotencyKey
            , idempotencyKey));
    }

    /**
     * 尝试创建待执行幂等记录；唯一键冲突时返回空。
     */
    public QuantExecutionRecord tryCreatePending(Long credentialId, Long strategyId, Long strategyInstanceId, String operationType, String symbol, String idempotencyKey,
                                                 String clientOrderId) {
        QuantExecutionRecord entity = new QuantExecutionRecord();
        entity.setCredentialId(credentialId);
        entity.setStrategyId(strategyId);
        entity.setStrategyInstanceId(strategyInstanceId);
        entity.setOperationType(operationType);
        entity.setSymbol(symbol);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setClientOrderId(clientOrderId == null || clientOrderId.isBlank() ? idempotencyKey : clientOrderId);
        entity.setExecutionStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setRecoveryAttempts(0);
        entity.setPaperSettled(false);
        entity.setUpdatedAt(entity.getCreatedAt());
        try {
            mapper.insert(entity);
            return entity;
        } catch (DuplicateKeyException ex) {
            return null;
        }
    }

    /**
     * 把执行记录更新为已确认并保存交易所订单状态。
     */
    public void accepted(QuantExecutionRecord entity, Long exchangeOrderId, String exchangeStatus) {
        entity.setExchangeOrderId(exchangeOrderId);
        entity.setExecutionStatus("CONFIRMED");
        entity.setExchangeOrderStatus(exchangeStatus == null ? "ACCEPTED" : exchangeStatus);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    /**
     * 记录一次执行恢复尝试。
     */
    public void markRecoveryAttempt(QuantExecutionRecord entity) {
        entity.setRecoveryAttempts((entity.getRecoveryAttempts() == null ? 0 : entity.getRecoveryAttempts()) + 1);
        entity.setLastRecoveryAt(LocalDateTime.now());
        entity.setUpdatedAt(entity.getLastRecoveryAt());
        mapper.updateById(entity);
    }

    /**
     * 把未触达交易所的执行记录标记为失败。
     */
    public void failed(QuantExecutionRecord entity, String message) {
        entity.setExecutionStatus("FAILED");
        entity.setErrorMessage(message == null ? "未知执行异常" : message);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    /**
     * 把已触达交易所但响应未知的执行记录标记为 UNKNOWN。
     */
    public void markUnknown(QuantExecutionRecord entity, String message) {
        entity.setExecutionStatus("UNKNOWN");
        entity.setErrorMessage(message == null ? "无法确认交易所订单事实" : message);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }

    /**
     * 把经过事实核实的执行记录标记为 RECOVERED。
     */
    public void markRecovered(QuantExecutionRecord entity, Long exchangeOrderId, String exchangeStatus) {
        entity.setExchangeOrderId(exchangeOrderId);
        entity.setExecutionStatus("RECOVERED");
        if (exchangeStatus != null) {
            entity.setExchangeOrderStatus(exchangeStatus);
        }
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
    }
}
