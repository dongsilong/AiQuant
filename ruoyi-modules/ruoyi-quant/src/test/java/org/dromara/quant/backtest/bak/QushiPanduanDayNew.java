package org.dromara.quant.backtest.bak;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FastExcel;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.dromara.common.core.utils.DateUtils;
import org.dromara.quant.backtest.KlineData;
import org.dromara.quant.backtest.TrendPeriod;
import org.ta4j.core.Bar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

@Slf4j
public class QushiPanduanDayNew {

    private static final String DATA_DIR = "G:\\eth-data\\ethusdt_1d_history";
    private static final String OUTPUT_FILE = "G:\\eth-data\\1d趋势数据.txt";
    private static final String EXCEL_OUTPUT_FILE = "G:\\eth-data\\1d_data_count.xlsx";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DecimalFormat DF = new DecimalFormat("0.00");

    private int countDuo = 0;
    private int countKong = 0;
    private int countZhendong = 0;
    private int numDuo = 0;
    private int numKong = 0;
    private int numZhendong = 0;
    private TrendType previousType;

    private List<TrendPeriod> duoPeriods = new ArrayList<>();
    private List<TrendPeriod> kongPeriods = new ArrayList<>();
    private List<TrendPeriod> zhendangPeriods = new ArrayList<>();

    private TrendPeriod currentPeriod;
    private int currentIndex;

    // 回踩相关状态
    private boolean ma5Touched;
    private boolean ma7Touched;
    private boolean ma15Touched;
    private boolean ma20Touched;
    private boolean ma30Touched;

    private double highestPriceAfterMa5Touch;
    private double highestPriceAfterMa7Touch;
    private double highestPriceAfterMa15Touch;
    private double highestPriceAfterMa20Touch;
    private double highestPriceAfterMa30Touch;

    private double lowestPriceAfterMa5Touch;
    private double lowestPriceAfterMa7Touch;
    private double lowestPriceAfterMa15Touch;
    private double lowestPriceAfterMa20Touch;
    private double lowestPriceAfterMa30Touch;

    public static void main(String[] args) {
        try {
            QushiPanduanDayNew analyzer = new QushiPanduanDayNew();
            analyzer.analyzeTrend();
        } catch (Exception e) {
            log.error("分析过程中发生错误", e);
        }
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

        SMAIndicator ma5 = new SMAIndicator(new ClosePriceIndicator(series), 5);
        SMAIndicator ma7 = new SMAIndicator(new ClosePriceIndicator(series), 7);
        SMAIndicator ma15 = new SMAIndicator(new ClosePriceIndicator(series), 15);
        SMAIndicator ma20 = new SMAIndicator(new ClosePriceIndicator(series), 20);
        SMAIndicator ma30 = new SMAIndicator(new ClosePriceIndicator(series), 30);

        for (int index = 0; index < allData.size(); index++) {
            Long openTime = allData.get(index).getOpenTime();
            Date date = new Date(openTime);
            String openTimeStr = DateUtils.formatDateTime(date);

            double closePrice = Double.parseDouble(allData.get(index).getClose());
            double highPrice = Double.parseDouble(allData.get(index).getHigh());
            double lowPrice = Double.parseDouble(allData.get(index).getLow());
            double ma5Value = Double.parseDouble(DF.format(ma5.getValue(index).doubleValue()));
            double ma7Value = Double.parseDouble(DF.format(ma7.getValue(index).doubleValue()));
            double ma15Value = Double.parseDouble(DF.format(ma15.getValue(index).doubleValue()));
            double ma20Value = Double.parseDouble(DF.format(ma20.getValue(index).doubleValue()));
            double ma30Value = Double.parseDouble(DF.format(ma30.getValue(index).doubleValue()));

            TrendType trend = judgeTrend(openTimeStr, closePrice, ma7Value, ma15Value, ma20Value, ma30Value);

            recordTrendPeriod(trend, index, openTimeStr, allData.get(index), closePrice, highPrice, lowPrice, ma5Value, ma7Value, ma15Value, ma20Value, ma30Value);
        }

        if (currentPeriod != null) {
            saveCurrentPeriod();
        }

        log.info("总次数/总根数：{}/{}", (numDuo + numKong + numZhendong), (countDuo + countKong + countZhendong));
        log.info("多头趋势次数/多头 K 线根数：{}/{}", numDuo, countDuo);
        log.info("空头趋势次数/空头 K 线根数：{}/{}", numKong, countKong);
        log.info("震荡次数/震荡 K 线根数：{}/{}", numZhendong, countZhendong);

        printTrendStatistics();
        exportToExcel();
    }

    /**
     * 加载所有数据
     */
    private List<KlineData> loadAllData() throws IOException {
        Path dataDir = Paths.get(DATA_DIR);

        if (!Files.exists(dataDir)) {
            log.error("数据目录不存在：{}", dataDir);
            return new ArrayList<>();
        }

        List<KlineData> allData = new ArrayList<>();

        Files.list(dataDir).filter(path -> path.toString().endsWith(".json")).sorted().forEach(path -> {
            try {
                byte[] bytes = Files.readAllBytes(path);
                String content = new String(bytes);

                KlineFileData fileData = MAPPER.readValue(content, KlineFileData.class);

                if (fileData.getData() != null && !fileData.getData().isEmpty()) {
                    allData.addAll(fileData.getData());
                } else {
                    log.warn("文件{}中没有数据", path.getFileName());
                }
            } catch (IOException e) {
                log.error("读取文件失败：{}", path.getFileName(), e);
            }
        });

        allData.sort(Comparator.comparingLong(KlineData::getOpenTime));

        return allData;
    }

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

    /**
     * 判断趋势类型
     */
    private TrendType judgeTrend(String openTimeStr, double closePrice, double ma7, double ma15, double ma20, double ma30) {
        //        boolean isBullish = ma7 > ma15 && ma15 > ma20 && ma20 > ma30;
        //        boolean isBearish = ma7 < ma15 && ma15 < ma20 && ma20 < ma30;
        boolean isBullish = ma7 > ma15 && ma15 > ma30;
        boolean isBearish = ma7 < ma15 && ma15 < ma30;

        if (isBullish) {
            if (previousType != TrendType.STRONG_BULL) {
                numDuo++;
            }
            countDuo++;
            previousType = TrendType.STRONG_BULL;
            return TrendType.STRONG_BULL;
        } else if (isBearish) {
            if (previousType != TrendType.STRONG_BEAR) {
                numKong++;
            }
            previousType = TrendType.STRONG_BEAR;
            countKong++;
            return TrendType.STRONG_BEAR;
        } else {
            if (previousType != TrendType.SIDEWAYS) {
                numZhendong++;
            }
            previousType = TrendType.SIDEWAYS;
            countZhendong++;
            return TrendType.SIDEWAYS;
        }
    }

    /**
     * 记录趋势时间段
     */
    private void recordTrendPeriod(TrendType trend, int index, String openTimeStr, KlineData data, double closePrice, double highPrice, double lowPrice, double ma5Value,
                                   double ma7Value, double ma15Value, double ma20Value, double ma30Value) {
        if (currentPeriod == null) {
            initCurrentPeriod(trend, index, openTimeStr, data, highPrice, lowPrice);
            checkMaTouches(trend, closePrice, lowPrice, highPrice, openTimeStr, ma5Value, ma7Value, ma15Value, ma20Value, ma30Value);
        } else if (currentPeriod.getTrendType() != trend) {
            finalizeCurrentPeriod();
            initCurrentPeriod(trend, index, openTimeStr, data, highPrice, lowPrice);
            checkMaTouches(trend, closePrice, lowPrice, highPrice, openTimeStr, ma5Value, ma7Value, ma15Value, ma20Value, ma30Value);
        } else {
            updateCurrentPeriod(index, openTimeStr, data, highPrice, lowPrice);
            checkMaTouches(trend, closePrice, lowPrice, highPrice, openTimeStr, ma5Value, ma7Value, ma15Value, ma20Value, ma30Value);
        }
    }

    /**
     * 初始化当前趋势段
     */
    private void initCurrentPeriod(TrendType trend, int index, String openTimeStr, KlineData data, double highPrice, double lowPrice) {
        currentIndex++;

        currentPeriod = new TrendPeriod();
        currentPeriod.setTrendType(trend);
        currentPeriod.setStartOpenTime(openTimeStr);
        currentPeriod.setStartCloseTime(DateUtils.formatDateTime(new Date(data.getOpenTime())));
        currentPeriod.setEndOpenTime(openTimeStr);
        currentPeriod.setEndCloseTime(DateUtils.formatDateTime(new Date(data.getCloseTime())));
        currentPeriod.setStartIndex(currentIndex);
        currentPeriod.setEndIndex(currentIndex);
        currentPeriod.setBarCount(1);

        double startPrice = Double.parseDouble(data.getOpen());
        currentPeriod.setStartPrice(startPrice);
        currentPeriod.setHighestPrice(highPrice);
        currentPeriod.setLowestPrice(lowPrice);

        resetMaTouchState();
    }

    /**
     * 更新当前趋势段
     */
    private void updateCurrentPeriod(int index, String openTimeStr, KlineData data, double highPrice, double lowPrice) {
        currentPeriod.setEndOpenTime(openTimeStr);
        currentPeriod.setEndCloseTime(DateUtils.formatDateTime(new Date(data.getCloseTime())));
        currentPeriod.setEndIndex(index + 1);
        currentPeriod.setBarCount(currentPeriod.getEndIndex() - currentPeriod.getStartIndex() + 1);

        if (highPrice > currentPeriod.getHighestPrice()) {
            currentPeriod.setHighestPrice(highPrice);
        }
        if (lowPrice < currentPeriod.getLowestPrice()) {
            currentPeriod.setLowestPrice(lowPrice);
        }

        updateHighestPriceAfterTouch(highPrice);
        updateLowestPriceAfterTouch(lowPrice);
    }

    /**
     * 完成当前趋势段
     */
    private void finalizeCurrentPeriod() {
        currentPeriod.setBarCount(currentPeriod.getEndIndex() - currentPeriod.getStartIndex() + 1);
        calculatePriceChange(currentPeriod);
        saveCurrentPeriod();
        resetMaTouchState();
    }

    /**
     * 重置 MA 回踩状态
     */
    private void resetMaTouchState() {
        ma5Touched = false;
        ma7Touched = false;
        ma15Touched = false;
        ma20Touched = false;
        ma30Touched = false;

        highestPriceAfterMa5Touch = 0;
        highestPriceAfterMa7Touch = 0;
        highestPriceAfterMa15Touch = 0;
        highestPriceAfterMa20Touch = 0;
        highestPriceAfterMa30Touch = 0;

        lowestPriceAfterMa5Touch = Double.MAX_VALUE;
        lowestPriceAfterMa7Touch = Double.MAX_VALUE;
        lowestPriceAfterMa15Touch = Double.MAX_VALUE;
        lowestPriceAfterMa20Touch = Double.MAX_VALUE;
        lowestPriceAfterMa30Touch = Double.MAX_VALUE;
    }

    /**
     * 检查是否回踩各均线
     */
    private void checkMaTouches(TrendType trend, double closePrice, double lowPrice, double highPrice, String openTimeStr, double ma5Value, double ma7Value, double ma15Value,
                                double ma20Value, double ma30Value) {
        checkMa5Touch(trend, lowPrice, highPrice, ma5Value, openTimeStr);
        checkMa7Touch(trend, lowPrice, highPrice, ma7Value, openTimeStr);
        checkMa15Touch(trend, lowPrice, highPrice, ma15Value, openTimeStr);
        checkMa20Touch(trend, lowPrice, highPrice, ma20Value, openTimeStr);
        checkMa30Touch(trend, lowPrice, highPrice, ma30Value, openTimeStr);
    }

    /**
     * 更新回踩后的最高价
     */
    private void updateHighestPriceAfterTouch(double highPrice) {
        if (ma5Touched && highPrice > highestPriceAfterMa5Touch) {
            highestPriceAfterMa5Touch = highPrice;
        }
        if (ma7Touched && highPrice > highestPriceAfterMa7Touch) {
            highestPriceAfterMa7Touch = highPrice;
        }
        if (ma15Touched && highPrice > highestPriceAfterMa15Touch) {
            highestPriceAfterMa15Touch = highPrice;
        }
        if (ma20Touched && highPrice > highestPriceAfterMa20Touch) {
            highestPriceAfterMa20Touch = highPrice;
        }
        if (ma30Touched && highPrice > highestPriceAfterMa30Touch) {
            highestPriceAfterMa30Touch = highPrice;
        }
    }

    /**
     * 更新回踩后的最低价
     */
    private void updateLowestPriceAfterTouch(double lowPrice) {
        if (ma5Touched && lowPrice < lowestPriceAfterMa5Touch) {
            lowestPriceAfterMa5Touch = lowPrice;
        }
        if (ma7Touched && lowPrice < lowestPriceAfterMa7Touch) {
            lowestPriceAfterMa7Touch = lowPrice;
        }
        if (ma15Touched && lowPrice < lowestPriceAfterMa15Touch) {
            lowestPriceAfterMa15Touch = lowPrice;
        }
        if (ma20Touched && lowPrice < lowestPriceAfterMa20Touch) {
            lowestPriceAfterMa20Touch = lowPrice;
        }
        if (ma30Touched && lowPrice < lowestPriceAfterMa30Touch) {
            lowestPriceAfterMa30Touch = lowPrice;
        }
    }

    /**
     * 检查是否回踩MA5
     */
    private void checkMa5Touch(TrendType trend, double lowPrice, double highPrice, double ma5Value, String openTimeStr) {
        if (ma5Touched || !isTrendType(trend)) {
            return;
        }

        if (lowPrice <= ma5Value && highPrice >= ma5Value) {
            currentPeriod.setMa5TouchPrice(ma5Value);
            currentPeriod.setMa5TouchTime(openTimeStr);
            ma5Touched = true;
            highestPriceAfterMa5Touch = highPrice;
            lowestPriceAfterMa5Touch = lowPrice;
        }
    }

    /**
     * 检查是否回踩MA7
     */
    private void checkMa7Touch(TrendType trend, double lowPrice, double highPrice, double ma7Value, String openTimeStr) {
        if (ma7Touched || !isTrendType(trend)) {
            return;
        }

        if (lowPrice <= ma7Value && highPrice >= ma7Value) {
            currentPeriod.setMa7TouchPrice(ma7Value);
            currentPeriod.setMa7TouchTime(openTimeStr);
            ma7Touched = true;
            highestPriceAfterMa7Touch = highPrice;
            lowestPriceAfterMa7Touch = lowPrice;
        }
    }

    /**
     * 检查是否回踩MA15
     */
    private void checkMa15Touch(TrendType trend, double lowPrice, double highPrice, double ma15Value, String openTimeStr) {
        if (ma15Touched || !isTrendType(trend)) {
            return;
        }

        if (lowPrice <= ma15Value && highPrice >= ma15Value) {
            currentPeriod.setMa15TouchPrice(ma15Value);
            currentPeriod.setMa15TouchTime(openTimeStr);
            ma15Touched = true;
            highestPriceAfterMa15Touch = highPrice;
            lowestPriceAfterMa15Touch = lowPrice;
        }
    }

    /**
     * 检查是否回踩MA20
     */
    private void checkMa20Touch(TrendType trend, double lowPrice, double highPrice, double ma20Value, String openTimeStr) {
        if (ma20Touched || !isTrendType(trend)) {
            return;
        }

        if (lowPrice <= ma20Value && highPrice >= ma20Value) {
            currentPeriod.setMa20TouchPrice(ma20Value);
            currentPeriod.setMa20TouchTime(openTimeStr);
            ma20Touched = true;
            highestPriceAfterMa20Touch = highPrice;
            lowestPriceAfterMa20Touch = lowPrice;
        }
    }

    /**
     * 检查是否回踩MA30
     */
    private void checkMa30Touch(TrendType trend, double lowPrice, double highPrice, double ma30Value, String openTimeStr) {
        if (ma30Touched || !isTrendType(trend)) {
            return;
        }

        if (lowPrice <= ma30Value && highPrice >= ma30Value) {
            currentPeriod.setMa30TouchPrice(ma30Value);
            currentPeriod.setMa30TouchTime(openTimeStr);
            ma30Touched = true;
            highestPriceAfterMa30Touch = highPrice;
            lowestPriceAfterMa30Touch = lowPrice;
        }
    }

    /**
     * 判断是否为趋势类型（多头或空头）
     */
    private boolean isTrendType(TrendType trend) {
        return trend == TrendType.STRONG_BULL || trend == TrendType.WEAK_BULL || trend == TrendType.STRONG_BEAR || trend == TrendType.WEAK_BEAR;
    }

    /**
     * 计算价格变化和收益比例
     */
    private void calculatePriceChange(TrendPeriod period) {
        switch (period.getTrendType()) {
            case STRONG_BULL:
            case WEAK_BULL:
                calculateBullishProfit(period);
                break;
            case STRONG_BEAR:
            case WEAK_BEAR:
                calculateBearishProfit(period);
                break;
            case SIDEWAYS:
                period.setPriceChange(period.getHighestPrice() - period.getLowestPrice());
                period.setPriceChangePercent(((period.getHighestPrice() - period.getLowestPrice()) / period.getStartPrice()) * 100);
                break;
        }
    }

    /**
     * 计算多头收益
     */
    private void calculateBullishProfit(TrendPeriod period) {
        period.setPriceChange(period.getHighestPrice() - period.getStartPrice());
        period.setPriceChangePercent(((period.getHighestPrice() - period.getStartPrice()) / period.getStartPrice()) * 100);

        if (period.getMa5TouchPrice() > 0 && highestPriceAfterMa5Touch > 0) {
            double profitPercent = ((highestPriceAfterMa5Touch - period.getMa5TouchPrice()) / period.getMa5TouchPrice()) * 100;
            period.setMa5ProfitPercent(profitPercent);
            period.setHighestPriceAfterMa5Touch(highestPriceAfterMa5Touch);
            if (lowestPriceAfterMa5Touch < Double.MAX_VALUE) {
                period.setLowestPriceAfterMa5Touch(lowestPriceAfterMa5Touch);
            }
        }

        if (period.getMa7TouchPrice() > 0 && highestPriceAfterMa7Touch > 0) {
            double profitPercent = ((highestPriceAfterMa7Touch - period.getMa7TouchPrice()) / period.getMa7TouchPrice()) * 100;
            period.setMa7ProfitPercent(profitPercent);
            period.setHighestPriceAfterMa7Touch(highestPriceAfterMa7Touch);
            if (lowestPriceAfterMa7Touch < Double.MAX_VALUE) {
                period.setLowestPriceAfterMa7Touch(lowestPriceAfterMa7Touch);
            }
        }

        if (period.getMa15TouchPrice() > 0 && highestPriceAfterMa15Touch > 0) {
            double profitPercent = ((highestPriceAfterMa15Touch - period.getMa15TouchPrice()) / period.getMa15TouchPrice()) * 100;
            period.setMa15ProfitPercent(profitPercent);
            period.setHighestPriceAfterMa15Touch(highestPriceAfterMa15Touch);
            if (lowestPriceAfterMa15Touch < Double.MAX_VALUE) {
                period.setLowestPriceAfterMa15Touch(lowestPriceAfterMa15Touch);
            }
        }

        if (period.getMa20TouchPrice() > 0 && highestPriceAfterMa20Touch > 0) {
            double profitPercent = ((highestPriceAfterMa20Touch - period.getMa20TouchPrice()) / period.getMa20TouchPrice()) * 100;
            period.setMa20ProfitPercent(profitPercent);
            period.setHighestPriceAfterMa20Touch(highestPriceAfterMa20Touch);
            if (lowestPriceAfterMa20Touch < Double.MAX_VALUE) {
                period.setLowestPriceAfterMa20Touch(lowestPriceAfterMa20Touch);
            }
        }

        if (period.getMa30TouchPrice() > 0 && highestPriceAfterMa30Touch > 0) {
            double profitPercent = ((highestPriceAfterMa30Touch - period.getMa30TouchPrice()) / period.getMa30TouchPrice()) * 100;
            period.setMa30ProfitPercent(profitPercent);
            period.setHighestPriceAfterMa30Touch(highestPriceAfterMa30Touch);
            if (lowestPriceAfterMa30Touch < Double.MAX_VALUE) {
                period.setLowestPriceAfterMa30Touch(lowestPriceAfterMa30Touch);
            }
        }
    }

    /**
     * 计算空头收益
     */
    private void calculateBearishProfit(TrendPeriod period) {
        period.setPriceChange(period.getStartPrice() - period.getLowestPrice());
        period.setPriceChangePercent(((period.getStartPrice() - period.getLowestPrice()) / period.getStartPrice()) * 100);

        if (period.getMa5TouchPrice() > 0 && lowestPriceAfterMa5Touch < Double.MAX_VALUE) {
            double profitPercent = ((period.getMa5TouchPrice() - lowestPriceAfterMa5Touch) / period.getMa5TouchPrice()) * 100;
            period.setMa5ProfitPercent(profitPercent);
            period.setLowestPriceAfterMa5Touch(lowestPriceAfterMa5Touch);
            if (highestPriceAfterMa5Touch > 0) {
                period.setHighestPriceAfterMa5Touch(highestPriceAfterMa5Touch);
            }
        }

        if (period.getMa7TouchPrice() > 0 && lowestPriceAfterMa7Touch < Double.MAX_VALUE) {
            double profitPercent = ((period.getMa7TouchPrice() - lowestPriceAfterMa7Touch) / period.getMa7TouchPrice()) * 100;
            period.setMa7ProfitPercent(profitPercent);
            period.setLowestPriceAfterMa7Touch(lowestPriceAfterMa7Touch);
            if (highestPriceAfterMa7Touch > 0) {
                period.setHighestPriceAfterMa7Touch(highestPriceAfterMa7Touch);
            }
        }

        if (period.getMa15TouchPrice() > 0 && lowestPriceAfterMa15Touch < Double.MAX_VALUE) {
            double profitPercent = ((period.getMa15TouchPrice() - lowestPriceAfterMa15Touch) / period.getMa15TouchPrice()) * 100;
            period.setMa15ProfitPercent(profitPercent);
            period.setLowestPriceAfterMa15Touch(lowestPriceAfterMa15Touch);
            if (highestPriceAfterMa15Touch > 0) {
                period.setHighestPriceAfterMa15Touch(highestPriceAfterMa15Touch);
            }
        }

        if (period.getMa20TouchPrice() > 0 && lowestPriceAfterMa20Touch < Double.MAX_VALUE) {
            double profitPercent = ((period.getMa20TouchPrice() - lowestPriceAfterMa20Touch) / period.getMa20TouchPrice()) * 100;
            period.setMa20ProfitPercent(profitPercent);
            period.setLowestPriceAfterMa20Touch(lowestPriceAfterMa20Touch);
            if (highestPriceAfterMa20Touch > 0) {
                period.setHighestPriceAfterMa20Touch(highestPriceAfterMa20Touch);
            }
        }

        if (period.getMa30TouchPrice() > 0 && lowestPriceAfterMa30Touch < Double.MAX_VALUE) {
            double profitPercent = ((period.getMa30TouchPrice() - lowestPriceAfterMa30Touch) / period.getMa30TouchPrice()) * 100;
            period.setMa30ProfitPercent(profitPercent);
            period.setLowestPriceAfterMa30Touch(lowestPriceAfterMa30Touch);
            if (highestPriceAfterMa30Touch > 0) {
                period.setHighestPriceAfterMa30Touch(highestPriceAfterMa30Touch);
            }
        }
    }

    /**
     * 保存当前趋势段
     */
    private void saveCurrentPeriod() {
        switch (currentPeriod.getTrendType()) {
            case STRONG_BULL:
            case WEAK_BULL:
                duoPeriods.add(currentPeriod);
                break;
            case STRONG_BEAR:
            case WEAK_BEAR:
                kongPeriods.add(currentPeriod);
                break;
            case SIDEWAYS:
                zhendangPeriods.add(currentPeriod);
                break;
        }
        currentPeriod = null;
    }

    /**
     * 打印趋势统计信息
     */
    private void printTrendStatistics() {
        log.info("\n==================== 多头趋势时间段统计 ====================");
        log.info("共 {} 个多头趋势阶段:", duoPeriods.size());
        log.info("{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{}", "序号"
            , "开始时间", "结束时间", "起始点位", "最高点位", "上涨点数", "涨幅%", "回踩MA5", "MA5后最高", "MA5收益%", "MA5后最低", "MA5损益%", "回踩MA7", "MA7后最高", "MA7收益%", "MA7后最低", "MA7损益%", "回踩MA15", "MA15" +
                "后最高", "MA15收益%", "MA15后最低", "MA15损益%", "回踩MA20", "MA20后最高", "MA20收益%", "MA20后最低", "MA20损益%", "回踩MA30", "MA30后最高", "MA30收益%", "MA30后最低", "MA30损益%", "持续周数");
        log.info(
            "-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
        for (int i = 0; i < duoPeriods.size(); i++) {
            TrendPeriod period = duoPeriods.get(i);
            printBullishPeriodInfo(i, period);
        }

        log.info("\n==================== 空头趋势时间段统计 ====================");
        log.info("共 {} 个空头趋势阶段:", kongPeriods.size());
        log.info("{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{}", "序号"
            , "开始时间", "结束时间", "起始点位", "最低点位", "下跌点数", "跌幅%", "回踩MA5", "MA5后最高", "MA5收益%", "MA5后最低", "MA5损益%", "回踩MA7", "MA7后最高", "MA7收益%", "MA7后最低", "MA7损益%", "回踩MA15", "MA15" +
                "后最高", "MA15收益%", "MA15后最低", "MA15损益%", "回踩MA20", "MA20后最高", "MA20收益%", "MA20后最低", "MA20损益%", "回踩MA30", "MA30后最高", "MA30收益%", "MA30后最低", "MA30损益%", "持续周数");
        log.info(
            "-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------");
        for (int i = 0; i < kongPeriods.size(); i++) {
            TrendPeriod period = kongPeriods.get(i);
            printBearishPeriodInfo(i, period);
        }

        log.info("\n==================== 震荡趋势时间段统计 ====================");
        log.info("共 {} 个震荡趋势阶段:", zhendangPeriods.size());
        log.info("{} \t{} \t{} \t{} \t{} \t{} \t{} \t{}", "序号", "开始时间", "结束时间", "起始点位", "最高点位", "最低点位", "振幅%", "持续周数");
        log.info("---------------------------------------------------------------------------------------------------");
        for (int i = 0; i < zhendangPeriods.size(); i++) {
            TrendPeriod period = zhendangPeriods.get(i);
            log.info("{} \t{} \t{} \t{} \t{} \t{} \t{}% \t{}", i + 1, period.getStartOpenTime(), period.getEndOpenTime(), DF.format(period.getStartPrice()),
                DF.format(period.getHighestPrice()), DF.format(period.getLowestPrice()), DF.format(period.getPriceChangePercent()), period.getBarCount());
        }

        log.info("\n==================== 趋势汇总 ====================");
        log.info("多头趋势总阶段数：{}", duoPeriods.size());
        log.info("空头趋势总阶段数：{}", kongPeriods.size());
        log.info("震荡趋势总阶段数：{}", zhendangPeriods.size());
        log.info("总趋势段数：{}", duoPeriods.size() + kongPeriods.size() + zhendangPeriods.size());
    }

    /**
     * 打印多头趋势段信息
     */
    private void printBullishPeriodInfo(int index, TrendPeriod period) {
        String ma5Info = formatMaTouchInfo(period.getMa5TouchPrice(), period.getMa5TouchTime());
        String ma5High = formatMaHigh(period.getHighestPriceAfterMa5Touch());
        String ma5Low = formatMaLow(period.getLowestPriceAfterMa5Touch());
        String ma5Profit = formatMaProfitAndLoss(period.getMa5TouchPrice(), period.getMa5ProfitPercent(), true);

        String ma7Info = formatMaTouchInfo(period.getMa7TouchPrice(), period.getMa7TouchTime());
        String ma7High = formatMaHigh(period.getHighestPriceAfterMa7Touch());
        String ma7Low = formatMaLow(period.getLowestPriceAfterMa7Touch());
        String ma7Profit = formatMaProfitAndLoss(period.getMa7TouchPrice(), period.getMa7ProfitPercent(), true);

        String ma15Info = formatMaTouchInfo(period.getMa15TouchPrice(), period.getMa15TouchTime());
        String ma15High = formatMaHigh(period.getHighestPriceAfterMa15Touch());
        String ma15Low = formatMaLow(period.getLowestPriceAfterMa15Touch());
        String ma15Profit = formatMaProfitAndLoss(period.getMa15TouchPrice(), period.getMa15ProfitPercent(), true);

        String ma20Info = formatMaTouchInfo(period.getMa20TouchPrice(), period.getMa20TouchTime());
        String ma20High = formatMaHigh(period.getHighestPriceAfterMa20Touch());
        String ma20Low = formatMaLow(period.getLowestPriceAfterMa20Touch());
        String ma20Profit = formatMaProfitAndLoss(period.getMa20TouchPrice(), period.getMa20ProfitPercent(), true);

        String ma30Info = formatMaTouchInfo(period.getMa30TouchPrice(), period.getMa30TouchTime());
        String ma30High = formatMaHigh(period.getHighestPriceAfterMa30Touch());
        String ma30Low = formatMaLow(period.getLowestPriceAfterMa30Touch());
        String ma30Profit = formatMaProfitAndLoss(period.getMa30TouchPrice(), period.getMa30ProfitPercent(), true);

        log.info("{} \t{} \t{} \t{} \t{} \t{} \t{}% \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} " +
            "\t{}", index + 1, period.getStartOpenTime(), period.getEndOpenTime(), DF.format(period.getStartPrice()), DF.format(period.getHighestPrice()),
            DF.format(period.getPriceChange()), DF.format(period.getPriceChangePercent()), ma5Info, ma5High, ma5Profit, ma5Low, formatMaLoss(period.getMa5TouchPrice(),
                period.getLowestPriceAfterMa5Touch(), true), ma7Info, ma7High, ma7Profit, ma7Low, formatMaLoss(period.getMa7TouchPrice(), period.getLowestPriceAfterMa7Touch(),
                true), ma15Info, ma15High, ma15Profit, ma15Low, formatMaLoss(period.getMa15TouchPrice(), period.getLowestPriceAfterMa15Touch(), true), ma20Info, ma20High,
            ma20Profit, ma20Low, formatMaLoss(period.getMa20TouchPrice(), period.getLowestPriceAfterMa20Touch(), true), ma30Info, ma30High, ma30Profit, ma30Low,
            formatMaLoss(period.getMa30TouchPrice(), period.getLowestPriceAfterMa30Touch(), true), period.getBarCount());
    }

    /**
     * 打印空头趋势段信息
     */
    private void printBearishPeriodInfo(int index, TrendPeriod period) {
        String ma5Info = formatMaTouchInfo(period.getMa5TouchPrice(), period.getMa5TouchTime());
        String ma5High = formatMaHigh(period.getHighestPriceAfterMa5Touch());
        String ma5Low = formatMaLow(period.getLowestPriceAfterMa5Touch());
        String ma5Profit = formatMaProfitAndLoss(period.getMa5TouchPrice(), period.getMa5ProfitPercent(), false);

        String ma7Info = formatMaTouchInfo(period.getMa7TouchPrice(), period.getMa7TouchTime());
        String ma7High = formatMaHigh(period.getHighestPriceAfterMa7Touch());
        String ma7Low = formatMaLow(period.getLowestPriceAfterMa7Touch());
        String ma7Profit = formatMaProfitAndLoss(period.getMa7TouchPrice(), period.getMa7ProfitPercent(), false);

        String ma15Info = formatMaTouchInfo(period.getMa15TouchPrice(), period.getMa15TouchTime());
        String ma15High = formatMaHigh(period.getHighestPriceAfterMa15Touch());
        String ma15Low = formatMaLow(period.getLowestPriceAfterMa15Touch());
        String ma15Profit = formatMaProfitAndLoss(period.getMa15TouchPrice(), period.getMa15ProfitPercent(), false);

        String ma20Info = formatMaTouchInfo(period.getMa20TouchPrice(), period.getMa20TouchTime());
        String ma20High = formatMaHigh(period.getHighestPriceAfterMa20Touch());
        String ma20Low = formatMaLow(period.getLowestPriceAfterMa20Touch());
        String ma20Profit = formatMaProfitAndLoss(period.getMa20TouchPrice(), period.getMa20ProfitPercent(), false);

        String ma30Info = formatMaTouchInfo(period.getMa30TouchPrice(), period.getMa30TouchTime());
        String ma30High = formatMaHigh(period.getHighestPriceAfterMa30Touch());
        String ma30Low = formatMaLow(period.getLowestPriceAfterMa30Touch());
        String ma30Profit = formatMaProfitAndLoss(period.getMa30TouchPrice(), period.getMa30ProfitPercent(), false);

        log.info("{} \t{} \t{} \t{} \t{} \t{} \t{}% \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} \t{} " +
            "\t{}", index + 1, period.getStartOpenTime(), period.getEndOpenTime(), DF.format(period.getStartPrice()), DF.format(period.getLowestPrice()),
            DF.format(period.getPriceChange()), DF.format(period.getPriceChangePercent()), ma5Info, ma5High, ma5Profit, ma5Low, formatMaLoss(period.getMa5TouchPrice(),
                period.getLowestPriceAfterMa5Touch(), false), ma7Info, ma7High, ma7Profit, ma7Low, formatMaLoss(period.getMa7TouchPrice(), period.getLowestPriceAfterMa7Touch(),
                false), ma15Info, ma15High, ma15Profit, ma15Low, formatMaLoss(period.getMa15TouchPrice(), period.getLowestPriceAfterMa15Touch(), false), ma20Info, ma20High,
            ma20Profit, ma20Low, formatMaLoss(period.getMa20TouchPrice(), period.getLowestPriceAfterMa20Touch(), false), ma30Info, ma30High, ma30Profit, ma30Low,
            formatMaLoss(period.getMa30TouchPrice(), period.getLowestPriceAfterMa30Touch(), false), period.getBarCount());
    }

    /**
     * 格式化 MA 回踩信息
     */
    private String formatMaTouchInfo(double price, String time) {
        return price > 0 ? DF.format(price) + "(" + time + ")" : "无";
    }

    /**
     * 格式化 MA 后最高价
     */
    private String formatMaHigh(double price) {
        return price > 0 ? DF.format(price) : "无";
    }

    /**
     * 格式化 MA 后最低价
     */
    private String formatMaLow(double price) {
        return price < Double.MAX_VALUE ? DF.format(price) : "无";
    }

    /**
     * 格式化 MA 收益
     */
    private String formatMaProfit(double touchPrice, double profitPercent) {
        return touchPrice > 0 ? DF.format(profitPercent) + "%" : "无";
    }

    /**
     * 格式化 MA 收益和损益（包含收益%和损益%）
     */
    private String formatMaProfitAndLoss(double touchPrice, double profitPercent, boolean isBullish) {
        if (touchPrice <= 0) {
            return "无";
        }
        return DF.format(profitPercent) + "%";
    }

    /**
     * 格式化 MA 损益
     */
    private String formatMaLoss(double touchPrice, double priceAfterTouch, boolean isBullish) {
        if (touchPrice <= 0 || priceAfterTouch <= 0 || priceAfterTouch == Double.MAX_VALUE) {
            return "无";
        }
        double lossPercent = ((priceAfterTouch - touchPrice) / touchPrice) * 100;
        return DF.format(lossPercent) + "%";
    }

    /**
     * 导出到 Excel
     */
    private void exportToExcel() {
        List<BullishTrendData> bullishDataList = convertToBullishData();
        List<BearishTrendData> bearishDataList = convertToBearishData();

        int totalSheets = 0;
        if (bullishDataList != null && !bullishDataList.isEmpty()) {
            totalSheets++;
        }
        if (bearishDataList != null && !bearishDataList.isEmpty()) {
            totalSheets++;
        }

        if (totalSheets == 0) {
            log.warn("没有可导出的数据");
            return;
        }

        try {
            File file = new File(EXCEL_OUTPUT_FILE);
            if (file.exists()) {
                file.delete();
            }

            try (ExcelWriter excelWriter = FastExcel.write(file).build()) {
                if (bullishDataList != null && !bullishDataList.isEmpty()) {
                    WriteSheet sheet1 = FastExcel.writerSheet("多头趋势").head(BullishTrendData.class).build();
                    excelWriter.write(bullishDataList, sheet1);
                    log.info("多头趋势数据导出成功，共 {} 条记录", bullishDataList.size());
                }

                if (bearishDataList != null && !bearishDataList.isEmpty()) {
                    WriteSheet sheet2 = FastExcel.writerSheet("空头趋势").head(BearishTrendData.class).build();
                    excelWriter.write(bearishDataList, sheet2);
                    log.info("空头趋势数据导出成功，共 {} 条记录", bearishDataList.size());
                }
            }

            log.info("\nExcel 文件已生成：{}，共 {} 个工作表", EXCEL_OUTPUT_FILE, totalSheets);
        } catch (IllegalArgumentException e) {
            log.error("导出 Excel 失败：参数错误 - {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("导出 Excel 失败：未知错误 - {}", e.getMessage(), e);
        }
    }

    /**
     * 导出单个工作表
     *
     * @param dataList  数据列表
     * @param sheetName 工作表名称
     * @param clazz     数据类型
     * @param <T>       数据类型泛型
     */
    private <T> void exportSheet(List<T> dataList, String sheetName, Class<T> clazz) {
        if (dataList == null || dataList.isEmpty()) {
            log.warn("导出数据为空：{}", sheetName);
            return;
        }

        log.info("准备导出工作表：'{}'，共 {} 条记录", sheetName, dataList.size());
    }

    /**
     * 转换为多头趋势数据
     */
    private List<BullishTrendData> convertToBullishData() {
        List<BullishTrendData> resultList = new ArrayList<>();
        for (int i = 0; i < duoPeriods.size(); i++) {
            TrendPeriod period = duoPeriods.get(i);
            BullishTrendData data = new BullishTrendData();
            data.setIndex(i + 1);
            data.setStartOpenTime(period.getStartOpenTime());
            data.setEndOpenTime(period.getEndOpenTime());
            data.setStartPrice(period.getStartPrice());
            data.setHighestPrice(period.getHighestPrice());
            data.setPriceChange(period.getPriceChange());
            data.setPriceChangePercent(formatPercent(period.getPriceChangePercent()));

            data.setMa5Touch(period.getMa5TouchPrice() > 0 ? period.getMa5TouchPrice() : null);
            data.setMa5AfterHigh(period.getHighestPriceAfterMa5Touch() > 0 ? period.getHighestPriceAfterMa5Touch() : null);
            data.setMa5AfterLow(period.getLowestPriceAfterMa5Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa5Touch() : null);
            data.setMa5ProfitPercent(period.getMa5ProfitPercent() > 0 ? formatPercent(period.getMa5ProfitPercent()) : null);
            data.setMa5LossPercent(calculateMaLossStr(period.getMa5TouchPrice(), period.getLowestPriceAfterMa5Touch(), true));

            data.setMa7Touch(period.getMa7TouchPrice() > 0 ? period.getMa7TouchPrice() : null);
            data.setMa7AfterHigh(period.getHighestPriceAfterMa7Touch() > 0 ? period.getHighestPriceAfterMa7Touch() : null);
            data.setMa7AfterLow(period.getLowestPriceAfterMa7Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa7Touch() : null);
            data.setMa7ProfitPercent(period.getMa7ProfitPercent() > 0 ? formatPercent(period.getMa7ProfitPercent()) : null);
            data.setMa7LossPercent(calculateMaLossStr(period.getMa7TouchPrice(), period.getLowestPriceAfterMa7Touch(), true));

            data.setMa15Touch(period.getMa15TouchPrice() > 0 ? period.getMa15TouchPrice() : null);
            data.setMa15AfterHigh(period.getHighestPriceAfterMa15Touch() > 0 ? period.getHighestPriceAfterMa15Touch() : null);
            data.setMa15AfterLow(period.getLowestPriceAfterMa15Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa15Touch() : null);
            data.setMa15ProfitPercent(period.getMa15ProfitPercent() > 0 ? formatPercent(period.getMa15ProfitPercent()) : null);
            data.setMa15LossPercent(calculateMaLossStr(period.getMa15TouchPrice(), period.getLowestPriceAfterMa15Touch(), true));

            data.setMa20Touch(period.getMa20TouchPrice() > 0 ? period.getMa20TouchPrice() : null);
            data.setMa20AfterHigh(period.getHighestPriceAfterMa20Touch() > 0 ? period.getHighestPriceAfterMa20Touch() : null);
            data.setMa20AfterLow(period.getLowestPriceAfterMa20Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa20Touch() : null);
            data.setMa20ProfitPercent(period.getMa20ProfitPercent() > 0 ? formatPercent(period.getMa20ProfitPercent()) : null);
            data.setMa20LossPercent(calculateMaLossStr(period.getMa20TouchPrice(), period.getLowestPriceAfterMa20Touch(), true));

            data.setMa30Touch(period.getMa30TouchPrice() > 0 ? period.getMa30TouchPrice() : null);
            data.setMa30AfterHigh(period.getHighestPriceAfterMa30Touch() > 0 ? period.getHighestPriceAfterMa30Touch() : null);
            data.setMa30AfterLow(period.getLowestPriceAfterMa30Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa30Touch() : null);
            data.setMa30ProfitPercent(period.getMa30ProfitPercent() > 0 ? formatPercent(period.getMa30ProfitPercent()) : null);
            data.setMa30LossPercent(calculateMaLossStr(period.getMa30TouchPrice(), period.getLowestPriceAfterMa30Touch(), true));

            data.setBarCount(period.getBarCount());
            resultList.add(data);
        }
        return resultList;
    }

    /**
     * 转换为空头趋势数据
     */
    private List<BearishTrendData> convertToBearishData() {
        List<BearishTrendData> resultList = new ArrayList<>();
        for (int i = 0; i < kongPeriods.size(); i++) {
            TrendPeriod period = kongPeriods.get(i);
            BearishTrendData data = new BearishTrendData();
            data.setIndex(i + 1);
            data.setStartOpenTime(period.getStartOpenTime());
            data.setEndOpenTime(period.getEndOpenTime());
            data.setStartPrice(period.getStartPrice());
            data.setLowestPrice(period.getLowestPrice());
            data.setPriceChange(period.getPriceChange());
            data.setPriceChangePercent(formatPercent(period.getPriceChangePercent()));

            data.setMa5Touch(period.getMa5TouchPrice() > 0 ? period.getMa5TouchPrice() : null);
            data.setMa5AfterHigh(period.getHighestPriceAfterMa5Touch() > 0 ? period.getHighestPriceAfterMa5Touch() : null);
            data.setMa5AfterLow(period.getLowestPriceAfterMa5Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa5Touch() : null);
            data.setMa5ProfitPercent(period.getMa5ProfitPercent() > 0 ? formatPercent(period.getMa5ProfitPercent()) : null);
            data.setMa5LossPercent(calculateMaLossStr(period.getMa5TouchPrice(), period.getLowestPriceAfterMa5Touch(), false));

            data.setMa7Touch(period.getMa7TouchPrice() > 0 ? period.getMa7TouchPrice() : null);
            data.setMa7AfterHigh(period.getHighestPriceAfterMa7Touch() > 0 ? period.getHighestPriceAfterMa7Touch() : null);
            data.setMa7AfterLow(period.getLowestPriceAfterMa7Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa7Touch() : null);
            data.setMa7ProfitPercent(period.getMa7ProfitPercent() > 0 ? formatPercent(period.getMa7ProfitPercent()) : null);
            data.setMa7LossPercent(calculateMaLossStr(period.getMa7TouchPrice(), period.getLowestPriceAfterMa7Touch(), false));

            data.setMa15Touch(period.getMa15TouchPrice() > 0 ? period.getMa15TouchPrice() : null);
            data.setMa15AfterHigh(period.getHighestPriceAfterMa15Touch() > 0 ? period.getHighestPriceAfterMa15Touch() : null);
            data.setMa15AfterLow(period.getLowestPriceAfterMa15Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa15Touch() : null);
            data.setMa15ProfitPercent(period.getMa15ProfitPercent() > 0 ? formatPercent(period.getMa15ProfitPercent()) : null);
            data.setMa15LossPercent(calculateMaLossStr(period.getMa15TouchPrice(), period.getLowestPriceAfterMa15Touch(), false));

            data.setMa20Touch(period.getMa20TouchPrice() > 0 ? period.getMa20TouchPrice() : null);
            data.setMa20AfterHigh(period.getHighestPriceAfterMa20Touch() > 0 ? period.getHighestPriceAfterMa20Touch() : null);
            data.setMa20AfterLow(period.getLowestPriceAfterMa20Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa20Touch() : null);
            data.setMa20ProfitPercent(period.getMa20ProfitPercent() > 0 ? formatPercent(period.getMa20ProfitPercent()) : null);
            data.setMa20LossPercent(calculateMaLossStr(period.getMa20TouchPrice(), period.getLowestPriceAfterMa20Touch(), false));

            data.setMa30Touch(period.getMa30TouchPrice() > 0 ? period.getMa30TouchPrice() : null);
            data.setMa30AfterHigh(period.getHighestPriceAfterMa30Touch() > 0 ? period.getHighestPriceAfterMa30Touch() : null);
            data.setMa30AfterLow(period.getLowestPriceAfterMa30Touch() < Double.MAX_VALUE ? period.getLowestPriceAfterMa30Touch() : null);
            data.setMa30ProfitPercent(period.getMa30ProfitPercent() > 0 ? formatPercent(period.getMa30ProfitPercent()) : null);
            data.setMa30LossPercent(calculateMaLossStr(period.getMa30TouchPrice(), period.getLowestPriceAfterMa30Touch(), false));

            data.setBarCount(period.getBarCount());
            resultList.add(data);
        }
        return resultList;
    }

    /**
     * 计算 MA 损益百分比（返回字符串）
     */
    private String calculateMaLossStr(double touchPrice, double priceAfterTouch, boolean isBullish) {
        if (touchPrice <= 0 || priceAfterTouch <= 0 || priceAfterTouch == Double.MAX_VALUE) {
            return null;
        }
        double lossPercent = ((priceAfterTouch - touchPrice) / touchPrice) * 100;
        return DF.format(lossPercent) + "%";
    }

    /**
     * 计算 MA 损益百分比（返回 Double）
     */
    private Double calculateMaLoss(double touchPrice, double priceAfterTouch, boolean isBullish) {
        if (touchPrice <= 0 || priceAfterTouch <= 0 || priceAfterTouch == Double.MAX_VALUE) {
            return null;
        }
        return ((priceAfterTouch - touchPrice) / touchPrice) * 100;
    }

    /**
     * 格式化百分比为字符串
     */
    private String formatPercent(double percent) {
        if (percent <= 0 && percent > -0.01) {
            return "0.00%";
        }
        return DF.format(percent) + "%";
    }

}
