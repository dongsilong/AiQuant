package org.dromara.quant.config.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 交易对元数据实体，保存价格/数量精度、步进和最小名义金额。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("quant_symbol")
public class QuantSymbol extends BaseEntity {
    /**
     * 序列化版本号。
     */
    @Serial
    private static final long serialVersionUID = 1L;
    /**
     * 主键。
     */
    @TableId
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
     * 最后同步时间。
     */
    private LocalDateTime syncedAt;
}
