package org.dromara.quant.execution;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import lombok.Data;

/**
 * 人工撤单 API 请求对象。
 */
@Data
public class CancelOrderRequest {
    /**
     * 客户端订单号。
     */
    @NotNull
    private Long credentialId;
    @NotBlank
    private String symbol;
    @NotNull
    private Long orderId;
    private String clientOrderId;
    @NotBlank
    private String idempotencyKey;
}
