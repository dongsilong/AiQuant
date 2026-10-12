package org.dromara.quant.strategy;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 策略纯计算上下文。只包含市场与当前持仓/运行状态快照，不暴露交易所 SDK、Mapper 或 Service。
 *
 * @param symbol                   交易对，例如 BTCUSDT
 * @param direction                策略方向，取 LONG 或 SHORT
 * @param entryPrice               平均开仓价
 * @param currentPrice             currentPrice 对应价格
 * @param referencePrice           策略参考价格
 * @param previousKlineOpenPrice   上一根参考 K 线开盘价
 * @param klineOpenTime            当前 K 线开始时间，也是同 K 线幂等时间基准
 * @param klineClosed              当前 K 线是否已收盘
 * @param eventTime                当前行情事件时间
 * @param quantity                 数量
 * @param initialPositionQuantity  首次开仓时的总仓位
 * @param initialMargin            开仓时初始保证金
 * @param leverage                 杠杆倍数
 * @param highestPriceSinceEntry   开仓后最高实时价
 * @param lowestPriceSinceEntry    开仓后最低实时价
 * @param firstTakeProfitTriggered 首次分批止盈是否已完成
 */
public record StrategyContext(String symbol, String direction, BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal referencePrice, BigDecimal previousKlineOpenPrice,
                              Instant klineOpenTime, boolean klineClosed, Instant eventTime, BigDecimal quantity, BigDecimal initialPositionQuantity, BigDecimal initialMargin,
                              Integer leverage, BigDecimal highestPriceSinceEntry, BigDecimal lowestPriceSinceEntry, boolean firstTakeProfitTriggered, int highestStep) {

    /** 兼容原完整构造器。 */
    public StrategyContext(String symbol, String direction, BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal referencePrice,
                           BigDecimal previousKlineOpenPrice, Instant klineOpenTime, boolean klineClosed, Instant eventTime,
                           BigDecimal quantity, BigDecimal initialPositionQuantity, BigDecimal initialMargin, Integer leverage,
                           BigDecimal highestPriceSinceEntry, BigDecimal lowestPriceSinceEntry, boolean firstTakeProfitTriggered) {
        this(symbol, direction, entryPrice, currentPrice, referencePrice, previousKlineOpenPrice, klineOpenTime, klineClosed, eventTime,
            quantity, initialPositionQuantity, initialMargin, leverage, highestPriceSinceEntry, lowestPriceSinceEntry, firstTakeProfitTriggered, 0);
    }

    /**
     * 兼容原有简单策略/测试的构造器。
     */
    public StrategyContext(String symbol, String direction, BigDecimal entryPrice, BigDecimal currentPrice, BigDecimal referencePrice, Instant klineOpenTime, boolean klineClosed
        , Instant eventTime, BigDecimal quantity, BigDecimal initialMargin) {
        this(symbol, direction, entryPrice, currentPrice, referencePrice, null, klineOpenTime, klineClosed, eventTime, quantity, quantity, initialMargin, 1, null, null, false);
    }
}
