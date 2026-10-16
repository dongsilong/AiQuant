package org.dromara.quant.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.DateUtils;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.aggregator.BaseBarSeriesAggregator;
import org.ta4j.core.aggregator.DurationBarAggregator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 金叉死叉回测
 * ma5、ma30、ma60
 * 10%开仓  10倍杠杆
 * <p>
 * 做多：ma5、ma30必须在ma60下面，ma5上穿ma60开多，止盈：ma5下穿ma30止盈，
 * 止损是总仓位3%，如果第二三根K线下穿ma60直接平仓
 * <p>
 * 做空：ma5、ma30必须在ma60上面，ma5下穿ma60开空，止盈：ma5上穿ma30止盈，
 * 止损是总仓位3%，如果第二三根K线上穿ma60直接平仓
 */
@Slf4j
public class GoldenOrDeathCrossOneHourBackTestBak {

    // ==================== 文件与格式化 ====================
    private static final String DATA_DIR_1m = "G:\\backTestData\\ethusdt\\1m";
    private static final String DATA_DIR_1h = "G:\\backTestData\\ethusdt\\1h";
    private static final String DATA_DIR_4h = "G:\\backTestData\\ethusdt\\4h";

    private static final String OUTPUT_FILE = "G:\\eth-data\\1d趋势数据.txt";
    private static final String EXCEL_OUTPUT_FILE = "G:\\eth-data\\1d_data_count.xlsx";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DecimalFormat DF = new DecimalFormat("0.00");

    // ==================== 交易参数 ====================
    /**
     * 初始资金
     */
    private static final double INITIAL_CAPITAL = 10_000.0;
    /**
     * 开仓占总仓位的比例（10%）
     */
    private static final double POSITION_RATIO = 0.10;
    /**
     * 杠杆倍数
     */
    private static final double LEVERAGE = 50.0;
    /**
     * 止损：价格反向 3%（因为杠杆 10 倍，10% 保证金 × 3% 价格波动 = 总仓位 3% 亏损）
     */
    private static final double STOP_LOSS_PCT = 0.03;
    /**
     * 第 2、3 根 K 线内跌破/上穿 MA60 直接平仓
     */
    private static final int EARLY_EXIT_BARS = 3;
    private final List<TrendPeriod> duoPeriods = new ArrayList<>();
    // ==================== 交易明细 ====================
    private final List<TradeRecord> tradeRecords = new ArrayList<>();
    // ==================== 趋势统计字段（原逻辑保留） ====================
    private int countDuo = 0;
    private int countKong = 0;
    private int countZhendong = 0;
    private int numDuo = 0;
    private int numKong = 0;
    private int numZhendong = 0;

    /**
     * 当前资金
     */
    private double capital = INITIAL_CAPITAL;
    /**
     * 峰值资金（用于回撤计算）
     */
    private double peakCapital = INITIAL_CAPITAL;
    /**
     * 最大回撤（0~1）
     */
    private double maxDrawdown = 0.0;
    /**
     * 持仓方向：1=多，-1=空，0=空仓
     */
    private int position = 0;
    /**
     * 开仓价
     */
    private double entryPrice = 0.0;
    /**
     * 名义仓位（保证金 × 杠杆）
     */
    private double positionNotional = 0.0;
    /**
     * 开仓后已经过的 K 线根数（开仓那根为 0）
     */
    private int barsHeld = 0;
    /**
     * 开仓时间戳（用于交易记录）
     */
    private long entryTime = 0L;
    // ==================== 上一根 K 线的指标值 ====================
    private double prevMa5 = Double.NaN;
    private double prevMa30 = Double.NaN;
    private double prevMa60 = Double.NaN;

    public static void main(String[] args) {
        try {
            GoldenOrDeathCrossOneHourBackTestBak analyzer = new GoldenOrDeathCrossOneHourBackTestBak();
            analyzer.analyzeTrend();
        } catch (Exception e) {
            log.error("分析过程中发生错误", e);
        }
    }

    // ==================== 程序入口 ====================

    public static BarSeries aggregateToHourly(BarSeries oneMinuteSeries) {
        // 1. 创建目标周期为1小时的聚合器
        //    onlyFinalBars = true 表示只输出已完成的完整1小时K线
        DurationBarAggregator hourlyAggregator = new DurationBarAggregator(Duration.ofHours(1), true);

        // 2. 创建序列聚合器
        BaseBarSeriesAggregator seriesAggregator = new BaseBarSeriesAggregator(hourlyAggregator);

        // 3. 执行聚合，返回新的1小时周期序列
        BarSeries hourlySeries = seriesAggregator.aggregate(oneMinuteSeries, "BTCUSDT_1h");

        return hourlySeries;
    }

    /**
     * 分析趋势
     */
    public void analyzeTrend() throws IOException {
        List<KlineData> allData = loadAllData();

        if (allData.isEmpty()) {
            log.error("未找到任何数据");
            return;
        }

        BaseBarSeries series = buildBarSeries(allData);
        //        SMAIndicator ma5 = new SMAIndicator(new ClosePriceIndicator(series), 5);
        //        SMAIndicator ma30 = new SMAIndicator(new ClosePriceIndicator(series), 30);
        //        SMAIndicator ma60 = new SMAIndicator(new ClosePriceIndicator(series), 60);

        BarSeries barSeriesHour = aggregateToHourly(series);
        SMAIndicator ma5 = new SMAIndicator(new ClosePriceIndicator(barSeriesHour), 5);
        SMAIndicator ma30 = new SMAIndicator(new ClosePriceIndicator(barSeriesHour), 30);
        SMAIndicator ma60 = new SMAIndicator(new ClosePriceIndicator(barSeriesHour), 60);

        for (int index = 0; index < allData.size(); index++) {

            Long openTime = allData.get(index).getOpenTime();
            Date date = new Date(openTime);

            double closePrice = Double.parseDouble(allData.get(index).getClose());
            double ma5Value = ma5.getValue(index).doubleValue();
            double ma30Value = ma30.getValue(index).doubleValue();
            double ma60Value = ma60.getValue(index).doubleValue();

            // 指标未形成，跳过
            if (Double.isNaN(ma5Value) || Double.isNaN(ma30Value) || Double.isNaN(ma60Value)) {
                continue;
            }

            ma5Value = Double.parseDouble(DF.format(ma5Value));
            ma30Value = Double.parseDouble(DF.format(ma30Value));
            ma60Value = Double.parseDouble(DF.format(ma60Value));

            goldenOrDeathCross(closePrice, ma5Value, ma30Value, ma60Value, openTime, allData.get(index).getCloseTime());
        }

        // 回测结束强制平仓
        if (position != 0) {
            KlineData last = allData.get(allData.size() - 1);
            double lastClose = Double.parseDouble(last.getClose());
            closePosition(lastClose, "回测结束强制平仓", last.getCloseTime());
        }

        log.info("总次数/总根数：{}/{}", (numDuo + numKong + numZhendong), (countDuo + countKong + countZhendong));
        log.info("多头趋势次数/多头 K 线根数：{}/{}", numDuo, countDuo);
        log.info("空头趋势次数/空头 K 线根数：{}/{}", numKong, countKong);
        log.info("震荡次数/震荡 K 线根数：{}/{}", numZhendong, countZhendong);

        printTradeStatistics();
        exportToExcel();
    }

    /**
     * 金叉死叉回测主循环
     *
     * <p>做多逻辑：
     * <ol>
     *   <li>开仓前 MA5、MA30 均在 MA60 下方；</li>
     *   <li>MA5 上穿 MA60 → 以总仓位 10% × 10 倍杠杆开多；</li>
     *   <li>止盈：MA5 下穿 MA30；</li>
     *   <li>止损：价格相对开仓价反向 3%（即总仓位亏损 3%）；</li>
     *   <li>保护：开仓后第 2、3 根 K 线若收盘跌破 MA60，直接平仓。</li>
     * </ol>
     *
     * <p>做空逻辑与做多完全对称。
     */
    private void goldenOrDeathCross(double closePrice, double ma5Value, double ma30Value, double ma60Value, long barOpenTime, long barCloseTime) {

        boolean prevValid = !Double.isNaN(prevMa5) && !Double.isNaN(prevMa30) && !Double.isNaN(prevMa60);

        // ==================== 一、持仓管理（先处理平仓） ====================
        if (position != 0) {
            barsHeld++;   // 当前 K 线为持仓后的第几根（开仓那根为 0）

            if (position == 1) {
                // ---------------- 多头持仓 ----------------
                double stopPrice = entryPrice * (1 - STOP_LOSS_PCT);

                if (closePrice <= stopPrice) {
                    closePosition(closePrice, "止损（反向 3%）", barCloseTime);
                } else if (prevValid && prevMa5 >= prevMa30 && ma5Value < ma30Value) {
                    closePosition(closePrice, "止盈：MA5 下穿 MA30", barCloseTime);
                } else if (barsHeld <= EARLY_EXIT_BARS && closePrice < ma60Value) {
                    closePosition(closePrice, "第 " + barsHeld + " 根 K 线下穿 MA60", barCloseTime);
                }

            } else if (position == -1) {
                // ---------------- 空头持仓 ----------------
                double stopPrice = entryPrice * (1 + STOP_LOSS_PCT);

                if (closePrice >= stopPrice) {
                    closePosition(closePrice, "止损（反向 3%）", barCloseTime);
                } else if (prevValid && prevMa5 <= prevMa30 && ma5Value > ma30Value) {
                    closePosition(closePrice, "止盈：MA5 上穿 MA30", barCloseTime);
                } else if (barsHeld <= EARLY_EXIT_BARS && closePrice > ma60Value) {
                    closePosition(closePrice, "第 " + barsHeld + " 根 K 线上穿 MA60", barCloseTime);
                }
            }
        }

        // ==================== 二、开仓判断（仅在空仓时） ====================
        if (position == 0 && prevValid) {

            // MA5 上穿 MA60
            boolean crossUpMa60 = prevMa5 <= prevMa60 && ma5Value > ma60Value;
            // MA5 下穿 MA60
            boolean crossDownMa60 = prevMa5 >= prevMa60 && ma5Value < ma60Value;

            // ---------- 做多：金叉 ----------
            // 前提：交叉前 MA5、MA30 均处于 MA60 下方
            if (crossUpMa60 && prevMa5 <= prevMa60 && prevMa30 < prevMa60) {
                openPosition(1, closePrice, "MA5 上穿 MA60（金叉开多）", barOpenTime);
            }
            // ---------- 做空：死叉 ----------
            // 前提：交叉前 MA5、MA30 均处于 MA60 上方
            else if (crossDownMa60 && prevMa5 >= prevMa60 && prevMa30 > prevMa60) {
                openPosition(-1, closePrice, "MA5 下穿 MA60（死叉开空）", barOpenTime);
            }
        }

        // ==================== 三、保存上一根指标值 ====================
        prevMa5 = ma5Value;
        prevMa30 = ma30Value;
        prevMa60 = ma60Value;
    }

    // ==================== 策略核心 ====================

    /**
     * 开仓：总仓位的 10% 作为保证金，10 倍杠杆
     */
    private void openPosition(int direction, double price, String reason, long barOpenTime) {
        if (position != 0) {
            return;
        }

        position = direction;
        entryPrice = price;
        barsHeld = 0;
        entryTime = barOpenTime;

        double margin = capital * POSITION_RATIO;   // 保证金 = 总仓位 × 10%
        positionNotional = margin * LEVERAGE;          // 名义仓位 = 保证金 × 10

        log.info("{}  [开仓] {}  价格={}  名义仓位={}  保证金={}  原因={}", DateUtils.date(entryTime), direction == 1 ? "多" : "空", DF.format(price), DF.format(positionNotional),
            DF.format(margin), reason);
    }

    /**
     * 平仓：按价格变动率结算盈亏
     * <pre>
     *   名义仓位 = 保证金 × 杠杆
     *   PnL     = 名义仓位 × (平仓价 - 开仓价) / 开仓价 × direction
     * </pre>
     */
    private void closePosition(double price, String reason, long barCloseTime) {
        if (position == 0) {
            return;
        }

        double pnlRate = (price - entryPrice) / entryPrice * position;
        double pnl = positionNotional * pnlRate;

        capital += pnl;
        if (capital > peakCapital) {
            peakCapital = capital;
        }
        double drawdown = (peakCapital - capital) / peakCapital;
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown;
        }

        TradeRecord tr = new TradeRecord();
        tr.direction = position;
        tr.entryPrice = entryPrice;
        tr.exitPrice = price;
        tr.pnl = pnl;
        tr.capitalAfter = capital;
        tr.barsHeld = barsHeld;
        tr.reason = reason;
        tr.entryTime = entryTime;
        tr.exitTime = barCloseTime;
        tradeRecords.add(tr);

        log.info("{}  [平仓] {}  开仓价={}  平仓价={}  盈亏={}  当前资金={}  持仓K线={}  原因={}", DateUtils.date(entryTime), position == 1 ? "多" : "空", DF.format(entryPrice), DF.format(price),
            DF.format(pnl), DF.format(capital), barsHeld, reason);

        // 复位
        position = 0;
        entryPrice = 0;
        positionNotional = 0;
        barsHeld = 0;
        entryTime = 0L;
    }

    /**
     * 加载所有数据
     */
    private List<KlineData> loadAllData() throws IOException {
        Path dataDir = Paths.get(DATA_DIR_1h);

        if (!Files.exists(dataDir)) {
            log.error("数据目录不存在：{}", dataDir);
            return new ArrayList<>();
        }

        List<KlineData> allData = new ArrayList<>();

        Files.list(dataDir).filter(path -> {
            String name = path.getFileName().toString().toLowerCase();
            return name.endsWith(".json");
        }).sorted().forEach(path -> {
            int fileCount = 0;
            int lineNo = 0;
            try (BufferedReader reader = Files.newBufferedReader(path)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        KlineData kline = MAPPER.readValue(line, KlineData.class);
                        allData.add(kline);
                        fileCount++;
                    } catch (Exception e) {
                        log.error("解析失败 {} 第 {} 行：{}", path.getFileName(), lineNo, e.getMessage());
                    }
                }
                log.info("已加载 {}，共 {} 条", path.getFileName(), fileCount);
            } catch (IOException e) {
                log.error("读取文件失败：{}", path.getFileName(), e);
            }
        });

        if (!allData.isEmpty()) {
            allData.sort(Comparator.comparingLong(KlineData::getOpenTime));
        }
        log.info("全部加载完成，总 K 线数：{}", allData.size());

        return allData;
    }

    // ==================== 数据加载 ====================

    /**
     * 构建 K 线序列
     */
    private BaseBarSeries buildBarSeries(List<KlineData> data) {
        BaseBarSeries series = new BaseBarSeriesBuilder().withName("ETHUSDT_1W").build();

        for (KlineData kline : data) {
            try {
                Instant beginTime = Instant.ofEpochMilli(kline.getOpenTime());
                Instant endTime = Instant.ofEpochMilli(kline.getCloseTime());

                Bar bar =
                    series.barBuilder().beginTime(beginTime).endTime(endTime).openPrice(kline.getOpen()).highPrice(kline.getHigh()).lowPrice(kline.getLow()).closePrice(kline.getClose()).volume(kline.getVolume()).build();

                series.addBar(bar);
            } catch (Exception e) {
                log.warn("解析 K 线数据失败：{}", e.getMessage());
            }
        }

        log.info("构建完成，总 K 线数：{}", series.getBarCount());
        return series;
    }

    // ==================== 统计输出 ====================

    /**
     * 打印交易统计
     */
    private void printTradeStatistics() {
        long longCount = tradeRecords.stream().filter(t -> t.direction == 1).count();
        long shortCount = tradeRecords.stream().filter(t -> t.direction == -1).count();
        long winCount = tradeRecords.stream().filter(t -> t.pnl > 0).count();
        long loseCount = tradeRecords.stream().filter(t -> t.pnl < 0).count();

        double totalPnl = tradeRecords.stream().mapToDouble(t -> t.pnl).sum();
        double winRate = tradeRecords.isEmpty() ? 0 : (double) winCount / tradeRecords.size() * 100;
        double avgWin = tradeRecords.stream().filter(t -> t.pnl > 0).mapToDouble(t -> t.pnl).average().orElse(0);
        double avgLose = tradeRecords.stream().filter(t -> t.pnl < 0).mapToDouble(t -> t.pnl).average().orElse(0);

        log.info("\n==================== 交易统计 ====================");
        log.info("初始资金：{}", DF.format(INITIAL_CAPITAL));
        log.info("最终资金：{}", DF.format(capital));
        log.info("累计盈亏：{}", DF.format(totalPnl));
        log.info("总交易次数：{}", tradeRecords.size());
        log.info("  做多 / 做空：{} / {}", longCount, shortCount);
        log.info("  盈利 / 亏损：{} / {}", winCount, loseCount);
        log.info("胜率：{}%", DF.format(winRate));
        log.info("平均盈利 / 平均亏损：{} / {}", DF.format(avgWin), DF.format(avgLose));
        log.info("最大回撤：{}%", DF.format(maxDrawdown * 100));
    }

    /**
     * 导出到 Excel
     */
    private void exportToExcel() {
        //        List<BullishTrendData> bullishDataList = convertToBullishData();
        //        List<BearishTrendData> bearishDataList = convertToBearishData();
        //
        //        int totalSheets = 0;
        //        if (bullishDataList != null && !bullishDataList.isEmpty()) {
        //            totalSheets++;
        //        }
        //        if (bearishDataList != null && !bearishDataList.isEmpty()) {
        //            totalSheets++;
        //        }
        //
        //        if (totalSheets == 0) {
        //            log.warn("没有可导出的数据");
        //            return;
        //        }
        //
        //        try {
        //            File file = new File(EXCEL_OUTPUT_FILE);
        //            if (file.exists()) {
        //                file.delete();
        //            }
        //
        //            try (ExcelWriter excelWriter = FastExcel.write(file).build()) {
        //                if (bullishDataList != null && !bullishDataList.isEmpty()) {
        //                    WriteSheet sheet1 = FastExcel.writerSheet("多头趋势")
        //                        .head(BullishTrendData.class)
        //                        .build();
        //                    excelWriter.write(bullishDataList, sheet1);
        //                    log.info("多头趋势数据导出成功，共 {} 条记录", bullishDataList.size());
        //                }
        //
        //                if (bearishDataList != null && !bearishDataList.isEmpty()) {
        //                    WriteSheet sheet2 = FastExcel.writerSheet("空头趋势")
        //                        .head(BearishTrendData.class)
        //                        .build();
        //                    excelWriter.write(bearishDataList, sheet2);
        //                    log.info("空头趋势数据导出成功，共 {} 条记录", bearishDataList.size());
        //                }
        //            }
        //
        //            log.info("\nExcel 文件已生成：{}，共 {} 个工作表", EXCEL_OUTPUT_FILE, totalSheets);
        //        } catch (IllegalArgumentException e) {
        //            log.error("导出 Excel 失败：参数错误 - {}", e.getMessage(), e);
        //        } catch (Exception e) {
        //            log.error("导出 Excel 失败：未知错误 - {}", e.getMessage(), e);
        //        }
    }

    // ==================== Excel 导出 ====================

    /**
     * 导出单个工作表
     */
    private <T> void exportSheet(List<T> dataList, String sheetName, Class<T> clazz) {
        if (dataList == null || dataList.isEmpty()) {
            log.warn("导出数据为空：{}", sheetName);
            return;
        }

        log.info("准备导出工作表：'{}'，共 {} 条记录", sheetName, dataList.size());
    }

    @Data
    private static class TradeRecord {
        /**
         * 1=多，-1=空
         */
        private int direction;
        private double entryPrice;
        private double exitPrice;
        private double pnl;
        private double capitalAfter;
        private int barsHeld;
        private String reason;
        private long entryTime;
        private long exitTime;
    }
}
