package org.dromara.quant.decision;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 交易决策日志分页查询条件。
 */
@Data
public class TradeDecisionQuery {
    private Long credentialId;
    private String strategyName;
    private Long strategyInstanceId;
    private String symbol;
    private String runMode;
    private String operation;
    private String status;
    private String reason;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime from;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime to;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
