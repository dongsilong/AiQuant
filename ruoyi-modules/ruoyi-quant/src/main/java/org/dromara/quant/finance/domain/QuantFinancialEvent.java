package org.dromara.quant.finance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易财务事件：资金费、手续费、已实现盈亏、返佣、划转等独立于成交表保存。
 */
@Data
@TableName("quant_financial_event")
public class QuantFinancialEvent {
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
     * 交易对，例如 BTCUSDT。
     */
    private String symbol;
    /**
     * 财务事件类型，例如 FUNDING_FEE、COMMISSION、REALIZED_PNL。
     */
    private String eventType;
    /**
     * 财务事件金额；正负方向遵循交易所语义。
     */
    private BigDecimal amount;
    /**
     * 资产代码，例如 USDT。
     */
    private String asset;
    /**
     * 交易所财务流水主键。
     */
    private Long exchangeTransactionId;
    /**
     * 交易所财务流水补充信息。
     */
    private String info;
    /**
     * 交易所事件发生时间。
     */
    private LocalDateTime eventTime;
    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
}
