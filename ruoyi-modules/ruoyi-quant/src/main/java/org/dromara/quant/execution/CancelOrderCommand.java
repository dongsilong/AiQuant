package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;

/**
 * 统一撤单命令，封装撤销交易所订单所需的订单标识和幂等信息。
 *
 * @param credentialId   交易凭证主键
 * @param credential     当前运行上下文使用的交易凭证
 * @param symbol         交易对，例如 BTCUSDT
 * @param orderId        交易所订单主键
 * @param clientOrderId  客户端订单号
 * @param idempotencyKey 执行幂等键，用于阻止重复交易请求
 * @param runMode        运行模式：PAPER、TESTNET 或 LIVE
 */
public record CancelOrderCommand(Long credentialId, ExchangeGateway.Credential credential, String symbol, Long orderId, String clientOrderId, String idempotencyKey,
                                 String runMode) {
}
