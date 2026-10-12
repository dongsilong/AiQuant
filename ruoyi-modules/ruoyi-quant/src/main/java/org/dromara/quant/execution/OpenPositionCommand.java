package org.dromara.quant.execution;

import org.dromara.quant.exchange.ExchangeGateway;

import java.math.BigDecimal;

/**
 * 统一开仓执行命令，封装一次开仓所需的凭证、策略归因、交易参数和安全上下文。
 *
 * @param credentialId       交易凭证主键
 * @param strategyId         策略定义主键；为空表示非策略来源
 * @param strategyInstanceId 策略运行实例主键；为空表示非策略来源
 * @param credential         当前运行上下文使用的交易凭证
 * @param symbol             交易对，例如 BTCUSDT
 * @param side               订单买卖方向，例如 BUY/SELL
 * @param type               信号、事件或订单类型
 * @param quantity           数量
 * @param price              订单或成交价格
 * @param positionSide       持仓方向，例如 LONG/SHORT/BOTH
 * @param idempotencyKey     执行幂等键，用于阻止重复交易请求
 * @param runMode            运行模式：PAPER、TESTNET 或 LIVE
 * @param symbolRule         当前交易对规则快照
 * @param leverage           杠杆倍数
 * @param marginMode         保证金模式，例如 ISOLATED/CROSSED
 * @param notional           信号价格计算的名义金额，不作为 MARKET 订单价格发送
 * @param validUntil         信号有效期；运行时传入行情新鲜度截止时间
 * @param safety             本次执行安全检查快照
 */
public record OpenPositionCommand(Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.Credential credential, String symbol, String side, String type,
                                  BigDecimal quantity, BigDecimal price, String positionSide, String idempotencyKey, String runMode, ExchangeGateway.SymbolRule symbolRule,
                                  Integer leverage, String marginMode, ExecutionSafetyContext safety, BigDecimal notional, java.time.Instant validUntil, java.time.Instant signalEventTime) {
    /** 保留已有命令构造方式；事件时间仅用于诊断，不重新生成行情时间。 */
    public OpenPositionCommand(Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.Credential credential,
                               String symbol, String side, String type, BigDecimal quantity, BigDecimal price, String positionSide,
                               String idempotencyKey, String runMode, ExchangeGateway.SymbolRule symbolRule, Integer leverage,
                               String marginMode, ExecutionSafetyContext safety, BigDecimal notional, java.time.Instant validUntil) {
        this(credentialId, strategyId, strategyInstanceId, credential, symbol, side, type, quantity, price, positionSide,
            idempotencyKey, runMode, symbolRule, leverage, marginMode, safety, notional, validUntil, null);
    }

    /** 兼容已有内部调用，避免改变订单价格语义。 */
    public OpenPositionCommand(Long credentialId, Long strategyId, Long strategyInstanceId, ExchangeGateway.Credential credential,
                               String symbol, String side, String type, BigDecimal quantity, BigDecimal price, String positionSide,
                               String idempotencyKey, String runMode, ExchangeGateway.SymbolRule symbolRule, Integer leverage,
                               String marginMode, ExecutionSafetyContext safety) {
        this(credentialId, strategyId, strategyInstanceId, credential, symbol, side, type, quantity, price, positionSide,
            idempotencyKey, runMode, symbolRule, leverage, marginMode, safety, price == null ? null : quantity.multiply(price), null);
    }
}
