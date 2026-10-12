package org.dromara.quant.backtest;

import lombok.Data;
import org.dromara.quant.backtest.bak.TrendType;

@Data
public class TrendPeriod {
    private TrendType trendType;
    private String startOpenTime;
    private String endOpenTime;
    private String startCloseTime;
    private String endCloseTime;
    private int startIndex;
    private int endIndex;
    private int barCount;
    private double startPrice;
    private double highestPrice;
    private double lowestPrice;
    private double priceChange;
    private double priceChangePercent;
    private double ma5TouchPrice;
    private String ma5TouchTime;
    private double highestPriceAfterMa5Touch;
    private double lowestPriceAfterMa5Touch;
    private double ma5ProfitPercent;
    private double ma7TouchPrice;
    private String ma7TouchTime;
    private double highestPriceAfterMa7Touch;
    private double lowestPriceAfterMa7Touch;
    private double ma7ProfitPercent;
    private double ma15TouchPrice;
    private String ma15TouchTime;
    private double highestPriceAfterMa15Touch;
    private double lowestPriceAfterMa15Touch;
    private double ma15ProfitPercent;
    private double ma20TouchPrice;
    private String ma20TouchTime;
    private double highestPriceAfterMa20Touch;
    private double lowestPriceAfterMa20Touch;
    private double ma20ProfitPercent;
    private double ma30TouchPrice;
    private String ma30TouchTime;
    private double highestPriceAfterMa30Touch;
    private double lowestPriceAfterMa30Touch;
    private double ma30ProfitPercent;

    // 第一次回踩MA5 和 MA7 的相关字段
    private double firstMa5TouchPrice;
    private String firstMa5TouchTime;
    private int firstMa5TouchIndex;
    private double firstMa7TouchPrice;
    private String firstMa7TouchTime;
    private int firstMa7TouchIndex;
    private double firstMa5Ma7PriceDiff;
    private double firstMa5Ma7PriceDiffPercent;
    private int firstMa5Ma7BarDiff;
    private double ma5ValueAtFirstMa7Touch;
    private double ma7ValueAtFirstMa5Touch;

    // 首次回踩MA5 后的极值和收益率
    private double highestPriceAfterFirstMa5Touch;
    private double lowestPriceAfterFirstMa5Touch;
    private double profitFromFirstMa5Touch;
    private double profitPercentFromFirstMa5Touch;

    // 首次回踩MA5时的 MA30 值及 MA5 与 MA30 的差值
    private double ma30ValueAtFirstMa5Touch;
    private double ma5Ma30Diff;
    private double ma5Ma30DiffPercent;

    // 首次回踩MA5时的 Bull 线轨道值
    private double bullUpperRailAtFirstMa5Touch;
    private double bullLowerRailAtFirstMa5Touch;
    private double bullRailDiff;
    private double bullRailDiffPercent;
}
