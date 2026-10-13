package org.dromara.quant.quant.backtest.bak;

import org.apache.fesod.sheet.annotation.ExcelProperty;

@lombok.Data
public class BearishTrendData {
    @ExcelProperty(value = "序号")
    private Integer index;

    @ExcelProperty(value = "开始时间")
    private String startOpenTime;

    @ExcelProperty(value = "结束时间")
    private String endOpenTime;

    @ExcelProperty(value = "起始点位")
    private Double startPrice;

    @ExcelProperty(value = "最低点位")
    private Double lowestPrice;

    @ExcelProperty(value = "下跌点数")
    private Double priceChange;

    @ExcelProperty(value = "跌幅%")
    private String priceChangePercent;

    @ExcelProperty(value = "回踩MA5")
    private Double ma5Touch;

    @ExcelProperty(value = "MA5后最高")
    private Double ma5AfterHigh;

    @ExcelProperty(value = "MA5收益%")
    private String ma5ProfitPercent;

    @ExcelProperty(value = "MA5后最低")
    private Double ma5AfterLow;

    @ExcelProperty(value = "MA5损益%")
    private String ma5LossPercent;

    @ExcelProperty(value = "回踩MA7")
    private Double ma7Touch;

    @ExcelProperty(value = "MA7后最高")
    private Double ma7AfterHigh;

    @ExcelProperty(value = "MA7收益%")
    private String ma7ProfitPercent;

    @ExcelProperty(value = "MA7后最低")
    private Double ma7AfterLow;

    @ExcelProperty(value = "MA7损益%")
    private String ma7LossPercent;

    @ExcelProperty(value = "回踩MA15")
    private Double ma15Touch;

    @ExcelProperty(value = "MA15后最高")
    private Double ma15AfterHigh;

    @ExcelProperty(value = "MA15收益%")
    private String ma15ProfitPercent;

    @ExcelProperty(value = "MA15后最低")
    private Double ma15AfterLow;

    @ExcelProperty(value = "MA15损益%")
    private String ma15LossPercent;

    @ExcelProperty(value = "回踩MA20")
    private Double ma20Touch;

    @ExcelProperty(value = "MA20后最高")
    private Double ma20AfterHigh;

    @ExcelProperty(value = "MA20收益%")
    private String ma20ProfitPercent;

    @ExcelProperty(value = "MA20后最低")
    private Double ma20AfterLow;

    @ExcelProperty(value = "MA20损益%")
    private String ma20LossPercent;

    @ExcelProperty(value = "回踩MA30")
    private Double ma30Touch;

    @ExcelProperty(value = "MA30后最高")
    private Double ma30AfterHigh;

    @ExcelProperty(value = "MA30收益%")
    private String ma30ProfitPercent;

    @ExcelProperty(value = "MA30后最低")
    private Double ma30AfterLow;

    @ExcelProperty(value = "MA30损益%")
    private String ma30LossPercent;

    @ExcelProperty(value = "持续周数")
    private Integer barCount;

    // 第一次回踩 MA5 和 MA7 的相关字段
    @ExcelProperty(value = "首次回踩 MA5")
    private Double firstMa5Touch;

    @ExcelProperty(value = "首次回踩 MA5 时间")
    private String firstMa5TouchTime;

    @ExcelProperty(value = "首次回踩 MA7")
    private Double firstMa7Touch;

    @ExcelProperty(value = "首次回踩 MA7 时间")
    private String firstMa7TouchTime;

    @ExcelProperty(value = "MA5/MA7 差值")
    private Double firstMa5Ma7PriceDiff;

    @ExcelProperty(value = "差值%")
    private String firstMa5Ma7PriceDiffPercent;

    @ExcelProperty(value = "间隔 K 线 数")
    private Integer firstMa5Ma7BarDiff;

    @ExcelProperty(value = "MA5 时 MA7 值")
    private Double ma7ValueAtFirstMa5Touch;

    @ExcelProperty(value = "MA7 时 MA5 值")
    private Double ma5ValueAtFirstMa7Touch;

    // 首次回踩MA5 后的极值和收益（空头用最低点）
    @ExcelProperty(value = "MA5 后最低")
    private Double lowestPriceAfterFirstMa5Touch;

    @ExcelProperty(value = "收益点数")
    private Double profitFromFirstMa5Touch;

    @ExcelProperty(value = "收益率%")
    private String profitPercentFromFirstMa5Touch;

    // Bull 线轨道相关
    @ExcelProperty(value = "Bull线上轨")
    private Double bullUpperRailAtFirstMa5Touch;

    @ExcelProperty(value = "Bull 线下轨")
    private Double bullLowerRailAtFirstMa5Touch;

    @ExcelProperty(value = "轨道差值")
    private Double bullRailDiff;

    @ExcelProperty(value = "轨道差%")
    private String bullRailDiffPercent;

    // MA30 相关
    @ExcelProperty(value = "MA30 值")
    private Double ma30ValueAtFirstMa5Touch;

    @ExcelProperty(value = "MA5-MA30 差")
    private Double ma5Ma30Diff;

    @ExcelProperty(value = "差值%")
    private String ma5Ma30DiffPercent;

}
