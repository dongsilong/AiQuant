package org.dromara.quant.finance.domain.vo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.quant.account.domain.QuantAccountSnapshot;
import org.dromara.quant.finance.domain.QuantFinancialEvent;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 交易财务事件：资金费、手续费、已实现盈亏、返佣、划转等独立于成交表保存。
 */
@Data
@AutoMapper(target = QuantFinancialEvent.class)
public class QuantFinancialEventVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 主键。
     */
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
     * 数据库中的成交时间（UTC）。
     */
    @JsonIgnore
    private LocalDateTime eventTime;
    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;

    /**
     * 返回给前端的成交时间（北京时间）。
     */
    @JsonProperty("tradeTime")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    public LocalDateTime getTradeTimeBeijing() {
        if (eventTime == null) {
            return null;
        }
        return eventTime.atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime();
    }
}
