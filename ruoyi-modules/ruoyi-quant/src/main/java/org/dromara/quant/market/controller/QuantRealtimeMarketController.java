package org.dromara.quant.market.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.quant.exchange.ExchangeGateway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 实时交易终端的公共行情查询接口，行情数据统一来自 Binance SDK 适配器。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/quant/market")
public class QuantRealtimeMarketController {
    /**
     * 交易所统一网关。
     */
    private final ExchangeGateway gateway;

    /**
     * 根据交易规则规范化价格。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping("/price")
    public R<ExchangeGateway.PriceSnapshot> price(@RequestParam String symbol) {
        return R.ok(gateway.queryPrice(symbol));
    }

    /**
     * 查询指定交易对和周期的历史 K 线。
     */
    @SaCheckPermission("quant:terminal:list")
    @GetMapping("/klines")
    public R<List<ExchangeGateway.Kline>> klines(@RequestParam String symbol, @RequestParam(defaultValue = "15m") String interval, @RequestParam(defaultValue = "100") int limit) {
        return R.ok(gateway.queryKlines(symbol, interval, Math.min(Math.max(limit, 1), 1000)));
    }
}
