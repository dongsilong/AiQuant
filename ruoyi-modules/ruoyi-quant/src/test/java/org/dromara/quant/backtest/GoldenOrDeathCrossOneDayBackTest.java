package org.dromara.quant.backtest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.DateUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 金叉死叉回测（1 分钟数据模拟实时 1 天 K 线，开平仓精确到分钟）
 *
 * <p>策略参数：
 * <ul>
 *   <li>指标：MA5 / MA30 / MA60（对应 5 天 / 30 天 / 60 天）</li>
 *   <li>仓位：总资金的 10% 作为保证金，10 倍杠杆</li>
 *   <li>止损：价格反向 {@link #STOP_LOSS_PCT}</li>
 * </ul>
 *
 * <p>做多逻辑：
 * <ol>
 *   <li>开仓前 MA5、MA30 均在 MA60 下方；</li>
 *   <li>MA5 上穿 MA60 → 在下一分钟开盘价开多；</li>
 *   <li>止盈：MA5 下穿 MA30；</li>
 *   <li>止损：价格相对开仓价反向 {@link #STOP_LOSS_PCT}（分钟级 high/low 精确触发）；</li>
 *   <li>保护：开仓后第 2、3 根完整 1 天 K 线收盘跌破 MA60 → 在下一根日 K 线开盘平仓。</li>
 * </ol>
 *
 * <p>做空逻辑与做多完全对称。
 *
 * <p>核心机制：
 * <ol>
 *   <li>逐根遍历 1 分钟 K 线，实时维护"正在形成的 1 天 K 线"；</li>
 *   <li>每分钟重新计算 MA5 / MA30 / MA60（含未完成的那根日 K 线）；</li>
 *   <li>开仓/止盈：分钟级实时 MA 判断，信号确认后下一分钟开盘执行；</li>
 *   <li>止损：分钟级 1 分钟 high/low 精确触发，下一分钟开盘执行；</li>
 *   <li>MA60 保护：仅在跨日（1 天 K 线收盘）判断，在下一根日 K 线开盘执行。</li>
 * </ol>
 */
@Slf4j
public class GoldenOrDeathCrossOneDayBackTest {

    // ==================== 数据目录与格式化 ====================
    private static final String DATA_DIR_1M = "G:\\backTestData\\ethusdt\\1m";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DecimalFormat DF = new DecimalFormat("0.00");

    /** 聚合周期（毫秒）：1 天 = 24 × 60 × 60 × 1000 */
    private static final long AGGREGATION_MS = 86_400_000L;

    /** 1 分钟（毫秒） */
    private static final long ONE_MINUTE_MS = 60_000L;

    /** 周期名称，用于日志和 reason */
    private static final String PERIOD_NAME = "1 天";

    // ==================== 交易参数 ====================
    /** 初始资金 */
    private static final double INITIAL_CAPITAL = 10_000.0;
    /** 开仓占总资金的比例（10%） */
    private static final double POSITION_RATIO = 0.10;
    /** 杠杆倍数（策略要求 10 倍） */
    private static final double LEVERAGE = 10.0;
    /** 止损：价格反向百分比（0.2 = 20%） */
    private static final double STOP_LOSS_PCT = 0.2;
    /** MA60 保护的有效 K 线范围：开仓后第 2 ~ 3 根完整 1 天 K 线 */
    private static final int EARLY_EXIT_BAR_START = 2;
    private static final int EARLY_EXIT_BAR_END = 3;

    // ==================== 日 K 线序列（最后一根为正在形成） ====================
    private final List<KlineData> periodBars = new ArrayList<>();
    private long currentBucket = Long.MIN_VALUE;

    // ==================== 上一分钟的 MA 值（用于判断分钟级交叉） ====================
    private double prevMa5 = Double.NaN;
    private double prevMa30 = Double.NaN;
    private double prevMa60 = Double.NaN;

    // ==================== 账户与持仓 ====================
    private double capital = INITIAL_CAPITAL;
    private double peakCapital = INITIAL_CAPITAL;
    private double maxDrawdown = 0.0;

    /** 持仓方向：1=多，-1=空，0=空仓 */
    private int position = 0;
    private double entryPrice = 0.0;
    private double positionNotional = 0.0;
    /** 已持有完整 1 天 K 线的根数，开仓时=0，每跨一天 +1 */
    private int barsHeld = 0;
    /** 开仓时间戳（下一分钟开盘，分钟级） */
    private long entryTime = 0L;

    private final List<TradeRecord> tradeRecords = new ArrayList<>();

    public static void main(String[] args) {
        try {
            new GoldenOrDeathCrossOneDayBackTest().analyzeTrend();
        } catch (Exception e) {
            log.error("分析过程中发生错误", e);
        }
    }

    // ==================== 主流程 ====================

    public void analyzeTrend() throws IOException {
        List<KlineData> oneMinuteData = loadAllData(DATA_DIR_1M);
        if (oneMinuteData.isEmpty()) {
            log.error("未找到任何 1 分钟数据");
            return;
        }
        oneMinuteData.sort(Comparator.comparingLong(KlineData::getOpenTime));

        log.info("开始逐分钟回测，共 {} 根 1 分钟数据", oneMinuteData.size());

        for (int i = 0; i < oneMinuteData.size(); i++) {
            KlineData m1 = oneMinuteData.get(i);
            long bucket = m1.getOpenTime() / AGGREGATION_MS;
            boolean isNewPeriod = (bucket != currentBucket);

            // ==================== 跨日处理 ====================
            if (isNewPeriod) {
                // ① 若有持仓，barsHeld 先 +1（上一根完整 1 天 K 线已完成）
                if (position != 0) {
                    barsHeld++;
                    // ② 检查 MA60 保护：用上一根完整 1 天 K 线的收盘价与 MA60
                    checkMa60ProtectionAtPeriodClose();
                }
                // ③ 添加新的 1 天 K 线（正在形成），更新 bucket
                periodBars.add(newPeriodBar(m1));
                currentBucket = bucket;
            } else {
                // 同一天：合并进当前正在形成的 1 天 K 线
                mergeIntoPeriodBar(periodBars.get(periodBars.size() - 1), m1);
            }

            // ==================== 每分钟实时计算 MA ====================
            double curMa5 = calcMA(periodBars, 5);
            double curMa30 = calcMA(periodBars, 30);
            double curMa60 = calcMA(periodBars, 60);

            if (Double.isNaN(curMa5) || Double.isNaN(curMa30) || Double.isNaN(curMa60)) {
                prevMa5 = curMa5;
                prevMa30 = curMa30;
                prevMa60 = curMa60;
                continue;
            }

            // ==================== 持仓中：分钟级止损与止盈 ====================
            if (position != 0) {
                checkMinuteExit(m1, curMa5, curMa30);
            }

            // ==================== 空仓中：分钟级开仓信号 ====================
            if (position == 0
                && !Double.isNaN(prevMa5) && !Double.isNaN(prevMa30) && !Double.isNaN(prevMa60)) {
                checkEntry(m1, curMa5, curMa30, curMa60);
            }

            // ==================== 保存 MA 作为下一分钟的 prev ====================
            prevMa5 = curMa5;
            prevMa30 = curMa30;
            prevMa60 = curMa60;
        }

        // 回测结束强制平仓（用最后一根 1 分钟 K 线的收盘价与下一分钟开盘时间）
        if (position != 0) {
            KlineData last = oneMinuteData.get(oneMinuteData.size() - 1);
            closePosition(last.getClose(), "回测结束强制平仓",
                last.getOpenTime() + ONE_MINUTE_MS);
        }

        printTradeStatistics();
    }

    // ==================== MA60 保护（跨日判断，第 2、3 根 K 线） ====================

    /**
     * 跨日时检查 MA60 保护，仅作用于开仓后的第 2、3 根完整 1 天 K 线。
     *
     * <p>判断依据：
     * <ul>
     *   <li>上一根完整 1 天 K 线的收盘价（已存放在 {@code periodBars} 最后一根里）</li>
     *   <li>{@code prevMa60}——上一分钟（即该 1 天 K 线最后一分钟）计算出的 MA60，
     *       正好等于"上一根完整 1 天 K 线收盘时的 MA60"</li>
     * </ul>
     *
     * <p>平仓时间取"下一根 1 天 K 线的开盘时刻"，即 {@code lastCompletedBar.openTime + AGGREGATION_MS}，
     * 语义为"上一根日 K 线收盘确认信号，下一根日 K 线开盘执行平仓"。
     */
    private void checkMa60ProtectionAtPeriodClose() {
        if (position == 0) {
            return;
        }
        if (barsHeld < EARLY_EXIT_BAR_START || barsHeld > EARLY_EXIT_BAR_END) {
            return;
        }
        if (Double.isNaN(prevMa60) || periodBars.isEmpty()) {
            return;
        }

        KlineData lastCompletedBar = periodBars.get(periodBars.size() - 1);
        double lastPeriodClose = lastCompletedBar.getClose();
        // 平仓时间 = 下一根日 K 线开盘时刻
        long nextPeriodOpenTime = lastCompletedBar.getOpenTime() + AGGREGATION_MS;

        if (position == 1 && lastPeriodClose < prevMa60) {
            closePosition(lastPeriodClose,
                "第 " + barsHeld + " 根 " + PERIOD_NAME + " K 线下穿 MA60",
                nextPeriodOpenTime);
        } else if (position == -1 && lastPeriodClose > prevMa60) {
            closePosition(lastPeriodClose,
                "第 " + barsHeld + " 根 " + PERIOD_NAME + " K 线上穿 MA60",
                nextPeriodOpenTime);
        }
    }

    // ==================== 开仓判断（每分钟） ====================

    private void checkEntry(KlineData m1, double curMa5, double curMa30, double curMa60) {
        // MA5 上穿 MA60
        boolean crossUpMa60 = prevMa5 <= prevMa60 && curMa5 > curMa60;
        // MA5 下穿 MA60
        boolean crossDownMa60 = prevMa5 >= prevMa60 && curMa5 < curMa60;

        double closePrice = m1.getClose();
        // 执行时间 = 下一分钟开盘
        long execTime = m1.getOpenTime() + ONE_MINUTE_MS;

        // 做多：金叉，且交叉前 MA30 在 MA60 下方
        if (crossUpMa60 && prevMa30 < prevMa60) {
            String reason = String.format(
                "MA5 上穿 MA60（金叉开多） | 触发前 MA5=%s, MA30=%s, MA60=%s, 现价=%s",
                DF.format(prevMa5), DF.format(prevMa30), DF.format(prevMa60), DF.format(closePrice));
            openPosition(1, closePrice, reason, execTime);
        }
        // 做空：死叉，且交叉前 MA30 在 MA60 上方
        else if (crossDownMa60 && prevMa30 > prevMa60) {
            String reason = String.format(
                "MA5 下穿 MA60（死叉开空） | 触发前 MA5=%s, MA30=%s, MA60=%s, 现价=%s",
                DF.format(prevMa5), DF.format(prevMa30), DF.format(prevMa60), DF.format(closePrice));
            openPosition(-1, closePrice, reason, execTime);
        }
    }

    // ==================== 平仓判断（每分钟，不含 MA60 保护） ====================

    private void checkMinuteExit(KlineData m1, double curMa5, double curMa30) {
        double high = m1.getHigh();
        double low = m1.getLow();
        double close = m1.getClose();
        // 执行时间 = 下一分钟开盘
        long execTime = m1.getOpenTime() + ONE_MINUTE_MS;

        // 止损百分比文案（动态，避免与 STOP_LOSS_PCT 不一致）
        String stopPctText = DF.format(STOP_LOSS_PCT * 100) + "%";

        if (position == 1) {
            // 1) 止损：用 1 分钟 low 精确触发
            double stopPrice = entryPrice * (1 - STOP_LOSS_PCT);
            if (low <= stopPrice) {
                closePosition(stopPrice, "止损（反向 " + stopPctText + "）", execTime);
                return;
            }
            // 2) 止盈：MA5 实时下穿 MA30
            if (!Double.isNaN(prevMa5) && !Double.isNaN(prevMa30)
                && prevMa5 >= prevMa30 && curMa5 < curMa30) {
                closePosition(close, "止盈：MA5 下穿 MA30", execTime);
            }

        } else if (position == -1) {
            // 1) 止损：用 1 分钟 high 精确触发
            double stopPrice = entryPrice * (1 + STOP_LOSS_PCT);
            if (high >= stopPrice) {
                closePosition(stopPrice, "止损（反向 " + stopPctText + "）", execTime);
                return;
            }
            // 2) 止盈：MA5 实时上穿 MA30
            if (!Double.isNaN(prevMa5) && !Double.isNaN(prevMa30)
                && prevMa5 <= prevMa30 && curMa5 > curMa30) {
                closePosition(close, "止盈：MA5 上穿 MA30", execTime);
            }
        }
    }

    // ==================== 1 天 K 线实时聚合 ====================

    private KlineData newPeriodBar(KlineData m1) {
        KlineData bar = new KlineData();
        bar.setOpenTime(m1.getOpenTime());
        bar.setCloseTime(m1.getOpenTime() + AGGREGATION_MS - 1);
        bar.setOpen(m1.getOpen());
        bar.setHigh(m1.getHigh());
        bar.setLow(m1.getLow());
        bar.setClose(m1.getClose());
        bar.setVolume(m1.getVolume());
        return bar;
    }

    private void mergeIntoPeriodBar(KlineData periodBar, KlineData m1) {
        double h = m1.getHigh();
        double l = m1.getLow();
        if (h > periodBar.getHigh()) {
            periodBar.setHigh(m1.getHigh());
        }
        if (l < periodBar.getLow()) {
            periodBar.setLow(m1.getLow());
        }
        periodBar.setClose(m1.getClose());
        double vol = periodBar.getVolume() + m1.getVolume();
        periodBar.setVolume(vol);
    }

    // ==================== MA 计算（按收盘价） ====================

    private double calcMA(List<KlineData> bars, int period) {
        if (bars.size() < period) {
            return Double.NaN;
        }
        double sum = 0.0;
        int start = bars.size() - period;
        for (int i = start; i < bars.size(); i++) {
            sum += bars.get(i).getClose();
        }
        return sum / period;
    }

    // ==================== 开仓 / 平仓 ====================

    private void openPosition(int direction, double price, String reason, long signalTime) {
        if (position != 0) {
            return;
        }

        position = direction;
        entryPrice = price;
        barsHeld = 0;
        entryTime = signalTime;

        double margin = capital * POSITION_RATIO;
        positionNotional = margin * LEVERAGE;

        log.info("[开仓] {}  开仓时间={}  价格={}  名义仓位={}  保证金={}  原因={}",
            direction == 1 ? "多" : "空",
            DateUtils.date(entryTime),
            DF.format(price), DF.format(positionNotional), DF.format(margin), reason);
    }

    private void closePosition(double price, String reason, long exitTime) {
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

        int dir = position;
        double openPrice = entryPrice;
        int held = barsHeld;
        long openTime = entryTime;

        TradeRecord tr = new TradeRecord();
        tr.direction = dir;
        tr.entryPrice = openPrice;
        tr.exitPrice = price;
        tr.pnl = pnl;
        tr.capitalAfter = capital;
        tr.barsHeld = held;
        tr.reason = reason;
        tr.entryTime = openTime;
        tr.exitTime = exitTime;
        tradeRecords.add(tr);

        log.info("[平仓] {}  开仓时间={}  平仓时间={}  开仓价={}  平仓价={}  盈亏={}  当前资金={}  持仓K线={}  原因={}",
            dir == 1 ? "多" : "空",
            DateUtils.date(openTime),
            DateUtils.date(exitTime),
            DF.format(openPrice), DF.format(price), DF.format(pnl),
            DF.format(capital), held, reason);

        position = 0;
        entryPrice = 0;
        positionNotional = 0;
        barsHeld = 0;
        entryTime = 0L;
    }

    // ==================== 数据加载 ====================

    private List<KlineData> loadAllData(String dataDirPath) throws IOException {
        Path dataDir = Paths.get(dataDirPath);
        if (!Files.exists(dataDir)) {
            log.error("数据目录不存在：{}", dataDir);
            return new ArrayList<>();
        }

        List<KlineData> allData = new ArrayList<>();

        Files.list(dataDir)
            .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".json"))
            .sorted()
            .forEach(path -> {
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
                            allData.add(MAPPER.readValue(line, KlineData.class));
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

    // ==================== 统计输出 ====================

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

    @Data
    private static class TradeRecord {
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

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class KlineData {
        private Long openTime;
        private Double open;
        private Double high;
        private Double low;
        private Double close;
        private Double volume;
        private Long closeTime;
        private Double quoteAssetVolume;
        private Integer numberOfTrades;
        private Double takerBuyBaseAssetVolume;
        private Double takerBuyQuoteAssetVolume;
    }
}
