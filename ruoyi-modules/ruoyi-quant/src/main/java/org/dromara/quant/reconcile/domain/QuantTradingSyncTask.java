package org.dromara.quant.reconcile.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 持久化的手动订单与成交历史同步任务。
 */
@Data
@TableName("quant_trading_sync_task")
public class QuantTradingSyncTask {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long credentialId;
    private String requestedSymbol;
    private String status;
    private Integer totalSymbolCount;
    private Integer completedSymbolCount;
    private Integer orderCount;
    private Integer tradeCount;
    private String currentSymbol;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
    private String workerToken;
    private LocalDateTime leaseUntil;
}
