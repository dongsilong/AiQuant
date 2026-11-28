package org.dromara.quant.account.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.common.translation.annotation.Translation;
import org.dromara.common.translation.constant.TransConstant;
import org.dromara.quant.account.domain.QuantTrade;
import org.dromara.quant.account.mapper.QuantTradeTimeMapper;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 成交记录展示对象。
 */
@Data
@AutoMapper(target = QuantTrade.class, uses = {QuantTradeTimeMapper.class})
public class QuantTradeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    /**
     * 交易凭证主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long credentialId;
    /**
     * 客户名称。
     */
    private String customerName;
    /**
     * 用户主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;
    /**
     * 用户名称。
     */
    @Translation(type = TransConstant.USER_ID_TO_NAME, mapper = "userId")
    private String userName;
    /**
     * 交易对，例如 BTCUSDT。
     */
    private String symbol;
    /**
     * 订单买卖方向，例如 BUY/SELL。
     */
    private String side;
    /**
     * Binance 成交返回的持仓方向，例如 LONG/SHORT/BOTH。
     */
    private String positionSide;
    /**
     * 数量。
     */
    private BigDecimal quantity;
    /**
     * 订单或成交价格。
     */
    private BigDecimal price;
    /**
     * 交易手续费。
     */
    private BigDecimal commission;
    /**
     * 已实现盈亏。
     */
    private BigDecimal realizedPnl;
    /**
     * 以 UTC 偏移量表示的成交时间，前端根据量化时区配置进行展示。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private OffsetDateTime tradeTime;
    /**
     * 交易所成交主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long exchangeTradeId;
    /**
     * 交易所订单主键。
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long exchangeOrderId;

}
