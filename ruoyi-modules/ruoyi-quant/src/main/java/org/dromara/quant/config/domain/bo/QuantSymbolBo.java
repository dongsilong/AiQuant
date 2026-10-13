package org.dromara.quant.config.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.quant.config.domain.QuantSymbol;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 交易对业务入参对象。
 */
@Data
@AutoMapper(target = QuantSymbol.class, reverseConvertGenerate = false)
public class QuantSymbolBo implements Serializable {
    /**
     * 序列化版本号。
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键。
     */
    @NotNull(message = "主键不能为空", groups = EditGroup.class)
    private Long id;

    /**
     * 交易对，例如 BTCUSDT。
     */
    @NotBlank(message = "交易对不能为空", groups = {AddGroup.class, EditGroup.class})
    private String symbol;

    /**
     * 计价资产，例如 USDT。
     */
    private String quoteAsset;
    /**
     * 当前用户是否收藏该交易对。
     */
    private Boolean favorite;
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
}
