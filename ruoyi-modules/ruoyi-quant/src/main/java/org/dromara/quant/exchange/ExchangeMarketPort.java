package org.dromara.quant.exchange;

import java.util.List;
import java.util.function.Consumer;

/**
 * 交易所行情端口。
 */
public interface ExchangeMarketPort {
    private static void requireSupportedMarket(String runMode) {
        if ("TESTNET".equalsIgnoreCase(runMode)) {
            throw new IllegalStateException("网关不支持测试网行情，拒绝回退正式网");
        }
    }

    ExchangeGateway.ExchangeInfo queryExchangeInfo();

    /**
     * 获取订单实际交易环境的规则；不支持环境规则的网关保留调用方规则。
     */
    default ExchangeGateway.SymbolRule queryTradingRule(ExchangeGateway.Credential credential, String symbol) {
        return null;
    }

    List<ExchangeGateway.Kline> queryKlines(String symbol, String interval, int limit);

    /**
     * 从指定开盘时间开始补齐断线期间的K线。
     */
    default List<ExchangeGateway.Kline> queryKlinesFrom(String symbol, String interval, java.time.Instant from, int limit) {
        return queryKlines(symbol, interval, limit);
    }

    /**
     * 按聚合成交序号补偿，不允许将历史成交当作新的交易信号。
     */
    default List<ExchangeGateway.MarketEvent> queryAggregateTradesFrom(String symbol, String interval, long fromId, int limit) {
        return List.of();
    }

    /**
     * 环境感知行情端口；未实现测试网的网关必须拒绝，不能回退正式网。
     */
    default List<ExchangeGateway.Kline> queryKlines(String runMode, String symbol, String interval, int limit) {
        return queryKlinesFrom(runMode, symbol, interval, null, limit);
    }

    default List<ExchangeGateway.Kline> queryKlinesFrom(String runMode, String symbol, String interval, java.time.Instant from, int limit) {
        requireSupportedMarket(runMode);
        return queryKlinesFrom(symbol, interval, from, limit);
    }

    default List<ExchangeGateway.MarketEvent> queryAggregateTradesFrom(String runMode, String symbol, String interval, long fromId, int limit) {
        requireSupportedMarket(runMode);
        return queryAggregateTradesFrom(symbol, interval, fromId, limit);
    }

    default ExchangeGateway.PriceSnapshot queryPrice(String runMode, String symbol) {
        requireSupportedMarket(runMode);
        return queryPrice(symbol);
    }

    default ExchangeGateway.MarketStreamSession subscribeMarket(String runMode, String symbol, String interval, Consumer<ExchangeGateway.MarketEvent> listener) {
        requireSupportedMarket(runMode);
        return subscribeMarket(symbol, interval, listener);
    }

    ExchangeGateway.PriceSnapshot queryPrice(String symbol);

    ExchangeGateway.MarketStreamSession subscribeMarket(String symbol, String interval, Consumer<ExchangeGateway.MarketEvent> listener);

    /**
     * 本地请求尚未发出，调用方可以退避后重试只读操作。
     */
    class RequestDeferredException extends IllegalStateException {
        public RequestDeferredException() {
            super("Binance REST 请求排队达到上限，延后执行");
        }
    }
}
