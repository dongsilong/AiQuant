package org.dromara.quant.execution;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 人工平仓 API 请求对象。
 */
@Data
public class ClosePositionRequest {
    /**
     * 交易凭证主键。
     */
    @NotNull
    private Long credentialId;
    /**
     * 交易对，例如 BTCUSDT。
     */
    @NotBlank
    private String symbol;
    /**
     * 订单买卖方向，例如 BUY/SELL。
     */
    @NotBlank
    private String side;
    /**
     * 持仓方向，例如 LONG/SHORT/BOTH。
     */
    @NotBlank
    private String positionSide;

    /**
     * 数量。
     */
    @NotNull
    @DecimalMin("0.00000001")
    private BigDecimal quantity;

    /**
     * 执行幂等键，用于阻止重复交易请求。
     */
    @NotBlank
    private String idempotencyKey;
}
