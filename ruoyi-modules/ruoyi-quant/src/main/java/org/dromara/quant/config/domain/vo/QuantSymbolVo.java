package org.dromara.quant.config.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.apache.fesod.sheet.annotation.format.DateTimeFormat;
import org.dromara.quant.config.domain.QuantSymbol;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易对展示对象。
 */
@Data
@AutoMapper(target = QuantSymbol.class)
public class QuantSymbolVo implements Serializable {
    /**
     * 序列化版本号。
     */
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 主键。
     */
    private Long id;
    /**
     * 交易对，例如 BTCUSDT。
     */
    private String symbol;
    /**
     * 计价资产，例如 USDT。
     */
    private String quoteAsset;
    /**
     * 价格精度。
     */
    private Integer pricePrecision;
    /**
     * 数量精度。
     */
    private Integer quantityPrecision;
    /**
     * 价格最小变动单位。
     */
    private BigDecimal priceTickSize;
    /**
     * 普通订单数量步进。
     */
    private BigDecimal quantityStep;
    /**
     * 市价订单数量步进。
     */
    private BigDecimal marketQuantityStep;
    /**
     * 最小名义下单金额。
     */
    private BigDecimal minNotional;
    /**
     * 交易所返回状态。
     */
    private String exchangeStatus;
    /**
     * 当前状态。
     */
    private String status;
    /**
     * 当前用户是否收藏该交易对。
     */
    private Boolean favorite;
    /**
     * 最后同步时间。
     */
    @DateTimeFormat("yyyy-MM-dd HH:mm:ss")
    private LocalDateTime syncedAt;
}
