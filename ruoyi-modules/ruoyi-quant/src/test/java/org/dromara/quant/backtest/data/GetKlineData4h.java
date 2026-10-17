package org.dromara.quant.backtest.data;

import com.binance.connector.client.common.ApiException;
import com.binance.connector.client.common.ApiResponse;
import com.binance.connector.client.common.configuration.ClientConfiguration;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.DerivativesTradingUsdsFuturesRestApiUtil;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.api.DerivativesTradingUsdsFuturesRestApi;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.Interval;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.KlineCandlestickDataItem;
import com.binance.connector.client.derivatives_trading_usds_futures.rest.model.KlineCandlestickDataResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.dromara.common.core.utils.DateUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 获取当年1分钟K线数据
 */
public class GetKlineData4h {

    private static final String SYMBOL = "ETHUSDT";
    private static final Interval INTERVAL = Interval.INTERVAL_4h;
    private static final Long LIMIT = 1500L;
    private static final int YEAR = LocalDate.now().getYear();
    private static final String OUTPUT_DIR = "G:\\backTestData\\ethusdt\\4h";
    private static final long ONE_MINUTE_MS = 60_000L;

    public static void main(String[] args) {
        ClientConfiguration config = DerivativesTradingUsdsFuturesRestApiUtil.getClientConfiguration();
        DerivativesTradingUsdsFuturesRestApi api = new DerivativesTradingUsdsFuturesRestApi(config);

        new File(OUTPUT_DIR).mkdirs();

        long now = System.currentTimeMillis();

        for (int month = 1; month <= 12; month++) {
            YearMonth ym = YearMonth.of(YEAR, month);

            long monthStart = ym.atDay(1).atTime(0, 0, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
            long monthEnd = ym.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();

            if (monthStart > now) {
                System.out.printf("跳过 %d-%02d（未来月份）%n", YEAR, month);
                continue;
            }
            if (monthEnd > now) {
                monthEnd = now;
            }

            System.out.printf("→ 开始下载 %d-%02d ...%n", YEAR, month);

            List<KlineCandlestickDataItem> klines = fetchKlinesForPeriod(api, monthStart, monthEnd);

            if (klines.isEmpty()) {
                System.out.printf("  %d-%02d 无数据%n", YEAR, month);
                continue;
            }

            String fileName = String.format("%s/%s_%s_%d-%02d.json", OUTPUT_DIR, SYMBOL, INTERVAL, YEAR, month);
            saveToJsonLines(klines, fileName);

            System.out.printf("  ✓ 已保存 %,d 条 → %s%n", klines.size(), fileName);
        }

        System.out.println("\n全年下载完成！");
    }

    /**
     * 分页拉取指定时间段的所有 1 分钟 K 线（累积所有批次）
     */
    private static List<KlineCandlestickDataItem> fetchKlinesForPeriod(DerivativesTradingUsdsFuturesRestApi api, long startTime, long endTime) {

        // 【关键修正】用 List 累积所有批次，而不是覆盖
        List<KlineCandlestickDataItem> all = new ArrayList<>();
        long cursor = startTime;

        while (cursor < endTime) {
            System.out.println(DateUtils.date(cursor) + "    " + DateUtils.date(endTime));
            try {
                ApiResponse<KlineCandlestickDataResponse> resp = api.klineCandlestickData(SYMBOL, INTERVAL, cursor, endTime, LIMIT);

                if (resp == null || resp.getData() == null) {
                    break;
                }

                KlineCandlestickDataResponse batch = resp.getData();

                if (batch.isEmpty()) {
                    break;
                }

                // 【关键修正】累积到 all 中
                all.addAll(batch);

                // 下一批从最后一条 K 线的开盘时间 + 1 分钟开始
                KlineCandlestickDataItem lastItem = batch.get(batch.size() - 1);
                long lastOpenTime = Long.parseLong(lastItem.get(0));
                long nextCursor = lastOpenTime + ONE_MINUTE_MS;

                // 防死循环
                if (nextCursor <= cursor) {
                    break;
                }
                cursor = nextCursor;

                // 不满 LIMIT 说明已经取完
                if (batch.size() < LIMIT) {
                    break;
                }

                // 限流，避免触发币安权重限制
                Thread.sleep(200);

            } catch (ApiException e) {
                System.err.println("API 异常，跳过当前月: " + e.getMessage());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return all;
    }

    /**
     * 写入 JSON Lines 文件，每行一条记录
     */
    private static void saveToJsonLines(List<KlineCandlestickDataItem> klines, String filePath) {
        Gson gson = new GsonBuilder().create();
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            for (KlineCandlestickDataItem item : klines) {
                // 将数组格式的 K 线数据映射为带字段名的对象
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("openTime", item.get(0));
                record.put("open", item.get(1));
                record.put("high", item.get(2));
                record.put("low", item.get(3));
                record.put("close", item.get(4));
                record.put("volume", item.get(5));
                record.put("closeTime", item.get(6));
                record.put("quoteAssetVolume", item.get(7));
                record.put("numberOfTrades", item.get(8));
                record.put("takerBuyBaseAssetVolume", item.get(9));
                record.put("takerBuyQuoteAssetVolume", item.get(10));

                writer.write(gson.toJson(record));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("写入失败: " + filePath + " — " + e.getMessage());
        }
    }
}
