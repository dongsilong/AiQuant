package org.dromara.quant.execution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跨实例幂等执行记录：执行状态与交易所订单状态严格分离。
 */
@Data
@TableName("quant_execution_record")
public class QuantExecutionRecord {
    /**
     * 主键。
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    /**
     * 交易凭证主键。
     */
    private Long credentialId;
    /**
     * 策略定义主键；为空表示非策略来源。
     */
    private Long strategyId;
    /**
     * 策略运行实例主键；为空表示非策略来源。
     */
    private Long strategyInstanceId;
    /**
     * 执行操作类型，例如 OPEN、CLOSE、CANCEL。
     */
    private String operationType;
    /**
     * 交易对，例如 BTCUSDT。
     */
    private String symbol;
    /**
     * 执行幂等键，用于阻止重复交易请求。
     */
    private String idempotencyKey;
    /**
     * 客户端订单号。
     */
    private String clientOrderId;
    /**
     * 触发本次开平仓的信号原因。
     */
    private String triggerReason;
    /**
     * 交易所订单主键。
     */
    private Long exchangeOrderId;
    /**
     * RESERVED/PENDING/CONFIRMED/UNKNOWN/FAILED/RECOVERED
     */
    private String executionStatus;
    /**
     * NEW/PARTIALLY_FILLED/FILLED/CANCELED/EXPIRED/REJECTED 等交易所订单状态。
     */
    private String exchangeOrderStatus;
    /**
     * 执行失败或未知状态的错误信息。
     */
    private String errorMessage;
    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
    /**
     * 恢复尝试次数。
     */
    private Integer recoveryAttempts;
    /**
     * 最近一次恢复检查时间。
     */
    private LocalDateTime lastRecoveryAt;
    /**
     * PAPER 执行是否已经完成本地结算。
     */
    private Boolean paperSettled;
    /**
     * 最后更新时间。
     */
    private LocalDateTime updatedAt;
}
